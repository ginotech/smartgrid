package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.sql.Time;
import java.util.*;

// TODO: Actually instantiate the PowerServer class and un-static everything for god's sake!
// TODO: Consider converting PowerServer to abstract class and implementing each scheduler as an inherited class? maybe?
public class PowerServer {
    
    static final int GRANT_FREQUENCY = 500;     // How often to send grant packets (milliseconds)
    static final int SERVER_PORT = 1234;        // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 12; // Size of the request packet in bytes
    static final int DEFAULT_QUANTUM = 10;

    static InetAddress myAddr = null;
    static InetAddress destAddr = null;
    static int currentLoad = 0;
    static int maxLoad;
    static int quantum = DEFAULT_QUANTUM;
    static List<PowerRequest> clientList;
    static int priorityClientIndex = 0;
    static PowerLog log;

    // ^ Should this be here, or in main()?

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: java njit.smartgrid.PowerServer <server address> <broadcast address> <capacity> [quantum]");
            if (System.getProperty("os.name").contains("Linux")) {
                System.out.println("IMPORTANT: export _JAVA_OPTIONS=\"-Djava.net.preferIPv4Stack=true\"");
            }
            System.exit(0);
        }
        myAddr = InetAddress.getByName(args[0]);
        destAddr = InetAddress.getByName(args[1]);
        if (myAddr == null || destAddr == null) {
            System.err.println("Invalid server address or broadcast address.");
            System.exit(1);
        }
        maxLoad = Integer.parseInt(args[2]);
        if (args.length >= 4) {
            quantum = Integer.parseInt(args[3]);
            if (quantum < 1) {
                System.err.println("Quantum must be greater than 0!");
                System.exit(1);
            }
        }
        System.out.println("Broadcasting to " + destAddr.getHostAddress());
        System.out.println("Capacity: " + maxLoad);
        System.out.println("Quantum " + quantum);

        log = new PowerLog(true);
        clientList = new LinkedList<PowerRequest>();

        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
        // This should execute in a background thread and not affect listenForRequest()
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (!clientList.isEmpty()) {
                    updateGrantAmount();// Decrement grant quantity
                    grantPower();       // Grant more requests, if the capacity exists
                    sendGrantPacket();  // Send grant broadcast
                }
            }
        }, GRANT_FREQUENCY, GRANT_FREQUENCY);
        listenForRequest();
    }
    
    // Wait for an authorization request from a client
    public static void listenForRequest() {
        try (DatagramSocket receiveSocket = new DatagramSocket(SERVER_PORT)) {
            while (true) {
                byte[] packetDataArray = new byte[REQUEST_PACKET_LENGTH];
                try {
                    DatagramPacket packet = new DatagramPacket(packetDataArray, REQUEST_PACKET_LENGTH);
                    receiveSocket.receive(packet);
                    InetAddress clientAddr = packet.getAddress();
                    // If we sent the packet, ignore it!
                    if (clientAddr.equals(myAddr)) {
                        continue;
                    }
                    if (packet.getLength() == REQUEST_PACKET_LENGTH) {
                        // TODO: Validate the request packet before doing anything with it!
                        ByteBuffer packetData = ByteBuffer.wrap(packet.getData());
                        long clientTime = packetData.getLong();
                        int powerRequested = packetData.getInt();
                        printTimestamp();
                        System.out.format("Request from %s for %d: ", clientAddr.toString(), powerRequested);
                        if (addRequest(clientAddr, powerRequested)) {
                            System.out.println("Authorized.");
                        } else {
                            System.out.println("Rejected.");
                        }
                        log.logRequest(clientAddr, 0, powerRequested, clientTime);
                    }
                    else {
                        System.err.println("Invalid request packet of length " + packet.getLength());
                    }
                } catch (IOException e) {
                    System.err.println("IOException: " + e.getMessage());
                    System.exit(1);
                }
            }
        } catch (SocketException e) {
            System.err.println("SocketException: " + e.getMessage());
            System.exit(1);
        }
    }
    
    // Decide if we want to authorize a power request
    public static boolean addRequest(InetAddress clientAddr, int powerRequested) {
        if (powerRequested > 0) {
            PowerRequest clientRequest = new PowerRequest(clientAddr, powerRequested);
            clientList.add(clientRequest);   // Add the request to the queue
            return true;
        }
        return false;
    }

    public static void updateGrantAmount() {
        ListIterator<PowerRequest> it = clientList.listIterator();
        while (it.hasNext()) {
            PowerRequest entry = it.next();
            if (entry.getPowerGranted() > 0) {
                entry.decrementPowerGranted();
                if (entry.getPowerGranted() == 0) {
                    currentLoad--;
                }
            }
        }
    }

    // Decrement authorization amounts (also writes logfile)
    public static void grantPower() {
        // Start iterating through the client list, beginning with the current priority client
        ListIterator<PowerRequest> it  = clientList.listIterator(priorityClientIndex);
        int shiftClientIndex = 0;
        do {
            PowerRequest entry = it.next();
            if (entry.getPowerGranted() == 0) {
                int powerRequested = entry.getPowerRequested();
                if (powerRequested > 0) {
                    if (currentLoad < maxLoad) {
                        currentLoad++;
                        if (powerRequested <= quantum) {
                            entry.setPowerGranted(powerRequested);
                            entry.setPowerRequested(0);
                            if (it.previousIndex() == priorityClientIndex) {
                                shiftClientIndex++;
                            }
                        }
                        else {
                            entry.setPowerGranted(quantum);
                            entry.setPowerRequested(powerRequested - quantum);
                        }
                    }
                }
            }
            if (!it.hasNext()) {
                it = clientList.listIterator();
            }
        } while (it.nextIndex() != priorityClientIndex);
        // Shift priority to next client
        priorityClientIndex = (priorityClientIndex + shiftClientIndex) % clientList.size();
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public static void sendGrantPacket() {
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerGrantPacket packet = new PowerGrantPacket(destAddr, clientList);
            sendSocket.send(packet.getPacket());
            printTimestamp();
            System.out.println("Sent grant packet. System load: " + currentLoad + "/" + maxLoad);
        } catch (UnknownHostException e) {
            System.err.println("UnknownHostException: " + e.getMessage());
            System.exit(1);
        } catch (SocketException e) {
            System.err.println("SocketException: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printTimestamp() {
        Time time = new Time(System.currentTimeMillis());
        System.out.print("[" + time.toString() + "] ");
    }
}