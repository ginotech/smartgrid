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

    static final int SCHEDULER_LIMIT        = 0;
    static final int SCHEDULER_FCFS         = 1;
    static final int SCHEDULER_ROUNDROBIN   = 2;

    static InetAddress myAddr = null;
    static InetAddress destAddr = null;
    static int maxLoad;
    static int schedulerType = SCHEDULER_LIMIT; // Default
    static int quantum = 0;
    static Queue<PowerRequest> clientsActive;
    static Queue<PowerRequest> clientsWaiting;
    static PowerLog log;

    // ^ Should this be here, or in main()?

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: java njit.smartgrid.PowerServer <server address> <broadcast address> <capacity> [scheduler] [scheduler option]");
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
            switch (args[3].toLowerCase()) {
                case "limit":
                    schedulerType = SCHEDULER_LIMIT;
                    break;
                case "fcfs":
                    schedulerType = SCHEDULER_FCFS;
                    break;
                case "roundrobin": case "rr":
                    if (args.length < 5) {
                        System.out.println("To use the round-robin scheduler you must specify the quantum (in # of packets.)");
                        System.exit(0);
                    }
                    schedulerType = SCHEDULER_ROUNDROBIN;
                    quantum = Integer.parseInt(args[4]);
                    if (quantum < 1) {
                        System.err.println("Quantum must be greater than 0!");
                        System.exit(1);
                    }
                    break;
                default:
                    schedulerType = SCHEDULER_LIMIT;
                    break;
            }
        }
        System.out.println("Broadcasting to " + destAddr.getHostAddress());
        System.out.println("Capacity: " + maxLoad);

        log = new PowerLog(true);

        clientsActive = new LinkedList<PowerRequest>();
        clientsWaiting = new LinkedList<PowerRequest>();
        
        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
        // This should execute in a background thread and not affect listenForRequest()
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                sendGrantPacket();  // Send grant broadcast
                updateAuthQueue();    // Decrement auth quantities and remove if no longer active
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
                        if (authorizeRequest(clientAddr, powerRequested)) {
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
    
    // Decide if we want to authorize a power request, and for how much
    public static boolean authorizeRequest(InetAddress clientAddr, int powerRequested) {
        if (powerRequested > 0) {
            PowerRequest clientRequest = new PowerRequest(clientAddr, powerRequested);
            if (schedulerType == SCHEDULER_ROUNDROBIN) {
                clientRequest.setPowerGranted(quantum);
            } else {
                clientRequest.setPowerGranted(powerRequested);
            }
            if (clientsActive.size() < maxLoad) {   // Do we have at least one slot open?
                clientsActive.add(clientRequest);   // Add the request to the active queue
                return true;
            } else {
                switch (schedulerType) {
                    case SCHEDULER_LIMIT:
                        // Throw away rejected request
                        return false;
                    case SCHEDULER_FCFS: case SCHEDULER_ROUNDROBIN:
                        // Add rejected request to "waiting" queue to be granted later
                        clientsWaiting.add(clientRequest);
                        return true;
                    default:
                        return false;
                }
            }
        }
        return false;
    }

    // Decrement authorization amounts and remove if zero (also writes logfile)
    public static void updateAuthQueue() {
        for (Iterator<PowerRequest> i = clientsActive.iterator(); i.hasNext();) {
            PowerRequest entry = i.next();
            // Write grants to log
            log.logGrant(entry.getAddress(), entry.getPowerGranted(), 0);
            int newPowerRequested = entry.getPowerRequested() - 1;
            // If authorized power amount is zero, remove the entry from the map
            if (newPowerRequested == 0) {
                i.remove();
            } else {
                entry.setPowerRequested(newPowerRequested);
                if (schedulerType == SCHEDULER_ROUNDROBIN) {
                    int newPowerGranted = entry.getPowerGranted() - 1;
                    if (newPowerGranted == 0) {         // If we've hit the quantum limit (haha)
                        entry.setPowerGranted(quantum); // then reset the quantum
                        clientsWaiting.add(entry);      // and put the request back in the waiting queue
                        i.remove();
                    } else {
                        entry.setPowerGranted(newPowerGranted);
                    }
                } else {
                    entry.setPowerGranted(entry.getPowerRequested());
                }
            }
        }
        switch (schedulerType) {
            case SCHEDULER_LIMIT:
                break;
            case SCHEDULER_FCFS: case SCHEDULER_ROUNDROBIN:
                // If we have room, move requests to active queue
                while (!(clientsWaiting.isEmpty()) && (clientsActive.size() < maxLoad)) {
                    clientsActive.add(clientsWaiting.remove());
                }
                break;
            default:
                break;
        }
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public static void sendGrantPacket() {
        // If there are no grants to be sent, don't do anything
        if (clientsActive.isEmpty()) {
            return;
        }
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerGrantPacket packet = new PowerGrantPacket(destAddr, clientsActive);
            sendSocket.send(packet.getPacket());
            printTimestamp();
            System.out.println("Sent grant packet. System load: " + clientsActive.size() + "/" + maxLoad);
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