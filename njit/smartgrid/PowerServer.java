package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.sql.Time;
import java.util.*;

public class PowerServer {
    
    private static final int GRANT_FREQUENCY = 1000;    // How often to send grant packets (milliseconds)
    static final int SERVER_PORT = 1234;                // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 16;        // Size of the request packet in bytes

    private InetAddress myAddr = null;
    private InetAddress destAddr = null;
    private int currentLoadWatts = 0;
    private final int maxLoadWatts;
    private Map<InetAddress, PowerRequest> clientMap;
    private int priorityClientIndex = 0;
    private InetAddress priorityClient;
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
        System.out.println("Capacity: " + maxLoad + "W");

        PowerServer powerServer = new PowerServer(myAddr, destAddr, maxLoad);
        powerServer.start();
    }

    public PowerServer(InetAddress myAddr, InetAddress destAddr, int maxLoadWatts) {
        this.myAddr = myAddr;
        this.destAddr = destAddr;
        this.maxLoadWatts = maxLoadWatts;

        this.log = new PowerLog(true);
        this.clientMap = new LinkedHashMap<>(); // LinkedHashMap preserves insertion order & prevents duplicates
    }


    public void start() {
        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (!clientMap.isEmpty()) {
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
                        int powerRequested = packetData.getInt();
                        int durationRequested = packetData.getInt();
                        printTimestamp();
                        System.out.format("Request from %s for %dW (%ds duration)\n", clientAddr.toString(), powerRequested, durationRequested);
                        addRequest(clientAddr, durationRequested, powerRequested);
                        log.logRequest(clientAddr, powerRequested, durationRequested, clientTime);
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
    private void addRequest(InetAddress clientAddr, int durationRequested, int powerRequested) {
        if (durationRequested > 0) {
            PowerRequest clientRequest = new PowerRequest(durationRequested, powerRequested);
            clientMap.put(clientAddr, clientRequest);   // Add the request to the queue or update if already present
        } else {
            System.err.println("Invalid duration requested.");
        }
    }

    // Iterate over the client map, updating grant durations and removing inactive clients from the current load total
    private void updateGrantAmount() {
        for (Map.Entry<InetAddress, PowerRequest> entry : clientMap.entrySet()) {
            if (entry.getValue().getDurationGranted() > 0) {
                entry.getValue().decrementDurationGranted();
                // If this satisfies the client's request, update the current load
                if (entry.getValue().getDurationGranted() == 0) {
                    currentLoadWatts -= entry.getValue().getPowerGranted();
                    // If the now-satisfied client had priority, shift priority to the next client
                    if (entry.getKey() == priorityClient) {
                        System.out.println("Changing priority");
                        incrementPriorityClient();
                    }
                }
            }
        }
    }

    public void grantPower() {
        // Start iterating through the client list, beginning with the current priority client
        Iterator<Map.Entry<InetAddress, PowerRequest>> it  = clientMap.entrySet().iterator();
        // Here we need to fast-forward the iterator to get to the priority client
        for (int i = 0; i < priorityClientIndex; i++) {
            it.next();
        }
        for (int i = 0; i < clientMap.size(); i++) {
            Map.Entry<InetAddress, PowerRequest> entry = it.next();
            if (i == 0) {
                priorityClient = entry.getKey();
                printTimestamp();
                System.out.println("Priority client: " + entry.getKey().getHostAddress());
            }
            int powerRequested = entry.getValue().getPowerRequested();
            int powerGranted = entry.getValue().getPowerGranted();
            // New request?
            if (entry.getValue().getDurationGranted() == 0) {
                int durationRequested = entry.getValue().getDurationRequested();
                if (durationRequested > 0) {
                    if ((powerRequested == PowerRequest.HIGH_POWER_WATTS) && (currentLoadWatts + PowerRequest.HIGH_POWER_WATTS <= maxLoadWatts)) {
                        powerGranted = PowerRequest.HIGH_POWER_WATTS;
                    } else if (currentLoadWatts + PowerRequest.LOW_POWER_WATTS <= maxLoadWatts) {
                        powerGranted = PowerRequest.LOW_POWER_WATTS;
                    }
                    if (powerGranted > 0) {
                        currentLoadWatts += powerGranted;
                        entry.getValue().setPowerGranted(powerGranted);
                        entry.getValue().setDurationGranted(durationRequested);
                        entry.getValue().setDurationRequested(0);
                    }
                }
            } else {
                // If we have excess capacity, see if the client wants to switch to high power
                if ((powerGranted == PowerRequest.LOW_POWER_WATTS) && (powerRequested == PowerRequest.HIGH_POWER_WATTS) && (currentLoadWatts + PowerRequest.HIGH_POWER_WATTS <= maxLoadWatts)) {
                    powerGranted = PowerRequest.HIGH_POWER_WATTS;
                    entry.getValue().setPowerGranted(powerGranted);
                    currentLoadWatts += PowerRequest.HIGH_POWER_WATTS - PowerRequest.LOW_POWER_WATTS;
                }
            }
            if (!it.hasNext()) {
                it = clientMap.entrySet().iterator();
            }
            if (entry.getValue().getDurationGranted() > 0) {
                log.logGrant(entry.getKey(), powerGranted, entry.getValue().getDurationGranted(), 0);
            }
        }
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public void sendGrantPacket() {
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerGrantPacket packet = new PowerGrantPacket(destAddr, clientMap);
            sendSocket.send(packet.getPacket());
            printTimestamp();
            System.out.format("Sent grant packet. System load: %dW (max %dW)\n", currentLoadWatts, maxLoadWatts);
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

    private void incrementPriorityClient() {
        priorityClientIndex = (priorityClientIndex + 1) % clientMap.size();
    }

    private void printTimestamp() {
        Time time = new Time(System.currentTimeMillis());
        System.out.print("[" + time.toString() + "] ");
    }
}