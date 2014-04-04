package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.sql.Time;
import java.util.*;

public class PowerServer {
    
    private static final int GRANT_FREQUENCY = 1000;    // How often to send grant packets (milliseconds)
    static final int SERVER_PORT = 1234;                // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 12;        // Size of the request packet in bytes

    private InetAddress myAddr = null;
    private InetAddress destAddr = null;
    private int currentLoadWatts = 0;
    private final int maxLoadWatts;
    private List<PowerRequest> clientList;
    private int priorityClientIndex = 0;
    private PowerLog log;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: java njit.smartgrid.PowerServer <server address> <broadcast address> <capacity>");
            if (System.getProperty("os.name").contains("Linux")) {
                System.out.println("IMPORTANT: export _JAVA_OPTIONS=\"-Djava.net.preferIPv4Stack=true\"");
            }
            System.exit(0);
        }
        final InetAddress myAddr = InetAddress.getByName(args[0]);
        final InetAddress destAddr = InetAddress.getByName(args[1]);
        if (myAddr == null || destAddr == null) {
            System.err.println("Invalid server address or broadcast address.");
            System.exit(1);
        }
        final int maxLoad = Integer.parseInt(args[2]);
        System.out.println("Broadcasting to " + destAddr.getHostAddress());
        System.out.println("Capacity: " + maxLoad);

        PowerServer powerServer = new PowerServer(myAddr, destAddr, maxLoad);
        powerServer.start();
    }

    public PowerServer(InetAddress myAddr, InetAddress destAddr, int maxLoadWatts) {
        this.myAddr = myAddr;
        this.destAddr = destAddr;
        this.maxLoadWatts = maxLoadWatts;

        this.log = new PowerLog(true);
        this.clientList = new LinkedList<PowerRequest>();
    }


    public void start() {
        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
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
    public void listenForRequest() {
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
                        int durationRequested = packetData.getInt();
                        printTimestamp();
                        System.out.format("Request from %s for %d: ", clientAddr.toString(), durationRequested);
                        addRequest(clientAddr, durationRequested);
                        log.logRequest(clientAddr, 0, durationRequested, clientTime);
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
    private void addRequest(InetAddress clientAddr, int durationRequested) {
        if (durationRequested > 0) {
            PowerRequest clientRequest = new PowerRequest(clientAddr, durationRequested, true);
            clientList.add(clientRequest);   // Add the request to the queue
        } else {
            System.err.println("Invalid duration requested.");
        }
    }

    private void updateGrantAmount() {
        ListIterator<PowerRequest> it = clientList.listIterator();
        while (it.hasNext()) {
            PowerRequest entry = it.next();
            if (entry.getDurationGranted() > 0) {
                entry.decrementDurationGranted();
                if (entry.getDurationGranted() == 0) {
                    currentLoadWatts -= entry.getPowerGranted();
                }
            }
        }
    }

    // Decrement authorization amounts (also writes logfile)
    public void grantPower() {
        // Start iterating through the client list, beginning with the current priority client
        ListIterator<PowerRequest> it  = clientList.listIterator(priorityClientIndex);
        int shiftClientIndex = 0;
        do {
            PowerRequest entry = it.next();
            if (entry.getDurationGranted() == 0) {
                int durationRequested = entry.getDurationRequested();
                int powerRequested = entry.getPowerRequested();
                int powerGranted = 0;
                if (durationRequested > 0) {
                    if ((powerRequested == PowerRequest.HIGH_POWER_WATTS) && (currentLoadWatts + PowerRequest.HIGH_POWER_WATTS <= maxLoadWatts)) {
                        powerGranted = PowerRequest.HIGH_POWER_WATTS;
                    } else if (currentLoadWatts + PowerRequest.LOW_POWER_WATTS <= maxLoadWatts) {
                        powerGranted = PowerRequest.LOW_POWER_WATTS;
                    }
                    if (powerGranted > 0) {
                        currentLoadWatts += powerGranted;
                        entry.setPowerGranted(powerGranted);
                        entry.setDurationGranted(durationRequested);
                        entry.setDurationRequested(0);
                        log.logGrant(entry.getAddress(), powerGranted, 0);
                    }
                    // If the priority client's request was satisfied completely, shift priority to next client
                    if ((it.previousIndex() == priorityClientIndex) && (powerGranted == powerRequested)) {
                        shiftClientIndex++;
                    }
                }
            }
            if (!it.hasNext()) {
                it = clientList.listIterator();
            }
        } while (it.nextIndex() != priorityClientIndex);
        // Shift priority to next client, if necessary
        priorityClientIndex = (priorityClientIndex + shiftClientIndex) % clientList.size();
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public void sendGrantPacket() {
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerGrantPacket packet = new PowerGrantPacket(destAddr, clientList);
            sendSocket.send(packet.getPacket());
            printTimestamp();
            System.out.println("Sent grant packet. System load: " + currentLoadWatts + "/" + maxLoadWatts);
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

    private void printTimestamp() {
        Time time = new Time(System.currentTimeMillis());
        System.out.print("[" + time.toString() + "] ");
    }
}