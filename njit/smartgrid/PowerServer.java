package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.sql.Time;
import java.util.*;

public class PowerServer {
    
    static final int GRANT_PERIOD = 1000;    // How often to send grant packets (milliseconds)
    static final int SERVER_PORT = 1234;                // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 16;        // Size of the request packet in bytes

    private InetAddress myAddr = null;
    private InetAddress destAddr = null;
    private int currentLoadWatts = 0;
    private final int maxLoadWatts;
    private Map<InetAddress, Queue<PowerRequest>> clientMap;
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
        log.logString(String.format("Server: %s:%d, Broadcast: %s:%d", myAddr.getHostAddress(), SERVER_PORT,
                destAddr.getHostAddress(), PowerGrantPacket.CLIENT_PORT));
        log.logString(String.format("Grant period (ms): %d", GRANT_PERIOD));
        log.logString(String.format("Capacity: %d, Available power levels: %d/%d/%d", maxLoadWatts, PowerRequest.POWER_BOTH,
                PowerRequest.POWER_HIGH, PowerRequest.POWER_LOW));
        // Sends a grant packet every GRANT_PERIOD (ms) comprised of all
        // requests since last grand packet was sent
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (!clientMap.isEmpty()) {
                    grantPower();       // Grant more requests, if the capacity exists
                    sendGrantPacket();  // Send grant broadcast
                    checkForInactiveClients();// Decrement grant quantity
                }
            }
        }, GRANT_PERIOD, GRANT_PERIOD);
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
                        ByteBuffer packetData = ByteBuffer.wrap(packet.getData());
                        long clientTime = packetData.getLong();
                        int powerRequested = packetData.getInt();
                        int packetsRequested = packetData.getInt();
                        printTimestamp();
                        System.out.format("Request: %s / %dW\n", clientAddr.toString(), powerRequested);
                        addRequest(clientAddr, packetsRequested, powerRequested);
                        log.logRequest(clientAddr, powerRequested, packetsRequested, clientTime);
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
    private void addRequest(InetAddress clientAddr, int packetsRequested, int powerRequested) {
        if (packetsRequested > 0) {
            // If the client already exists and has an active grant, then add this request automatically
            if (clientMap.containsKey(clientAddr) && !clientMap.get(clientAddr).isEmpty()) {
                PowerRequest newPowerRequest = new PowerRequest(packetsRequested, powerRequested);
                newPowerRequest.setPowerGranted(clientMap.get(clientAddr).peek().getPowerGranted());
                clientMap.get(clientAddr).add(newPowerRequest);
            } else {
                Queue<PowerRequest> requestQueue = new LinkedList<PowerRequest>();
                PowerRequest powerRequest = new PowerRequest(packetsRequested, powerRequested);
                requestQueue.add(powerRequest);
                clientMap.put(clientAddr, requestQueue);
            }
        } else {
            System.err.println("Invalid number of packets requested.");
        }
    }

    public void grantPower() {
        // Start iterating through the client list, beginning with the current priority client
        Iterator<Map.Entry<InetAddress, Queue<PowerRequest>>> it  = clientMap.entrySet().iterator();
        // Here we need to fast-forward the iterator to get to the priority client
        for (int i = 0; i < priorityClientIndex; i++) {
            it.next();
        }
        for (int i = 0; i < clientMap.size(); i++) {
            Map.Entry<InetAddress, Queue<PowerRequest>> client = it.next();
            // Is this the priority client?
            if (i == 0) {
                priorityClient = client.getKey();
            }
            int powerRequested = 0;
            int powerGranted = 0;
            int packetsRemaining = 0;
            PowerRequest powerRequest = client.getValue().peek();
            if (powerRequest != null) {
                powerRequested = powerRequest.getPowerRequested();
                powerGranted = powerRequest.getPowerGranted();
                // New (or continuing) request?
                if (powerRequest.getPacketsRemaining() == 0) {
                    int packetsRequested = powerRequest.getPacketsRequested();
                    if (packetsRequested > 0) {
                        // We have to ignore any power this client may already be using
                        currentLoadWatts -= powerGranted;
                        if ((powerRequested == PowerRequest.POWER_BOTH) && (currentLoadWatts + PowerRequest.POWER_BOTH <= maxLoadWatts)) {
                            powerGranted = PowerRequest.POWER_BOTH;
                        } else if ((powerRequested >= PowerRequest.POWER_HIGH) && (currentLoadWatts + PowerRequest.POWER_HIGH <= maxLoadWatts)) {
                            powerGranted = PowerRequest.POWER_HIGH;
                        } else if (currentLoadWatts + PowerRequest.POWER_LOW <= maxLoadWatts) {
                            powerGranted = PowerRequest.POWER_LOW;
                        }
                        if (powerGranted > 0) {
                            currentLoadWatts += powerGranted;
                            powerRequest.setPowerGranted(powerGranted);
                            powerRequest.setPacketsRemaining(packetsRequested);
                            powerRequest.setPacketsRequested(0);
                        }
                    }
                }
                // Decrement the number of packets remaining in preparation for sendGrantPacket
                powerRequest.decrementPacketsRemaining(); // Does not decrement below zero
                packetsRemaining = powerRequest.getPacketsRemaining();
            }
            // If we're at the end of the list, wrap around to the beginning
            if (!it.hasNext()) {
                it = clientMap.entrySet().iterator();
            }
            log.logGrant(client.getKey(), powerGranted, packetsRemaining, 0);
        }
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public void sendGrantPacket() {
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerGrantPacket packet = new PowerGrantPacket(destAddr, clientMap);
            sendSocket.send(packet.getPacket());
            printTimestamp();
            System.out.format("Load: %dW (max %dW)\n", currentLoadWatts, maxLoadWatts);
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

    // Iterate over the client map, updating grant amounts and removing inactive clients from the current load total
    private void checkForInactiveClients() {
        for (Map.Entry<InetAddress, Queue<PowerRequest>> client : clientMap.entrySet()) {
            PowerRequest powerRequest = client.getValue().peek();
            if (powerRequest != null) {
                if (powerRequest.getPacketsRemaining() == 0 && powerRequest.getPacketsRequested() == 0) {
                    // If the request is empty, pop it off the queue
                    int powerGranted = powerRequest.getPowerGranted();
                    client.getValue().poll();
                    // And if this satisfies the client's request, update the current load
                    if (client.getValue().isEmpty()) {
                        currentLoadWatts -= powerGranted;
                        if (client.getKey() == priorityClient) {
                            System.out.println("Changing priority");
                            incrementPriorityClient();
                        }
                    }
                }
            }
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
