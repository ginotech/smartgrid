package njit.smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.sql.Time;
import java.util.*;

public class PowerServer {
    
    static final int GRANT_PERIOD = 1000;    // How often to send grant packets (milliseconds)
    static final int SERVER_PORT = 1234;                // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 12;        // Size of the request packet in bytes

    private InetAddress myAddr = null;
    private InetAddress destAddr = null;
    private int currentLoadWatts = 0;
    private final int maxLoadWatts;
    private Map<InetAddress, List<PowerRequest>> clientMap;
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
        log.logString(String.format("Grant period: %dms", GRANT_PERIOD));
        log.logString(String.format("Capacity: %dW, Available power levels: %dW/%dW/%dW", maxLoadWatts, PowerRequest.POWER_BOTH,
                PowerRequest.POWER_HIGH, PowerRequest.POWER_LOW));
        // Sends a grant packet every GRANT_PERIOD (ms) comprised of all
        // requests since last grand packet was sent
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (!clientMap.isEmpty()) {
                    checkForInactiveClients();
                    grantPower();       // Grant more requests, if the capacity exists
                    removeDeniedRequests();
                }
                sendGrantPacket();  // Send grant broadcast
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
//                        printTimestamp();
//                        System.out.format("Request: %s @ %dW\n", clientAddr.toString(), powerRequested);
                        addRequest(clientAddr, powerRequested);
                        log.logRequest(clientAddr, powerRequested, clientTime);
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
    private void addRequest(InetAddress clientAddr, int powerRequested) {
        // If the client already exists and has an active grant, then add this request automatically
        // FIXME: non-empty request list does not guarantee an active grant. should check powergranted value also.
        if (clientMap.containsKey(clientAddr) && !clientMap.get(clientAddr).isEmpty()) {
            PowerRequest newPowerRequest = new PowerRequest(powerRequested);
            // FIXME: what if powerRequested of this request != powerGranted of last request?
            newPowerRequest.setPowerGranted(clientMap.get(clientAddr).get(0).getPowerGranted());
            clientMap.get(clientAddr).add(newPowerRequest);
        } else {
            List<PowerRequest> requestList = new LinkedList<>();
            PowerRequest powerRequest = new PowerRequest(powerRequested);
            requestList.add(powerRequest);
            clientMap.put(clientAddr, requestList);
        }
    }

    // Iterate over the client map, removing inactive clients from the current load total
    private void checkForInactiveClients() {
        for (Map.Entry<InetAddress, List<PowerRequest>> client : clientMap.entrySet()) {
            if (!client.getValue().isEmpty()) {
                PowerRequest powerRequest = client.getValue().get(0);
                if (powerRequest.getPowerGranted() > 0) {
                    // If the request has been granted, remove it from the list
                    int powerGranted = powerRequest.getPowerGranted();
                    client.getValue().remove(0);
                    // And if this satisfies the current request, update the load total
                    if (client.getValue().isEmpty() || client.getValue().get(0).getPowerGranted() == 0) {
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

    public void grantPower() {
        // Start iterating through the client list, beginning with the current priority client
        Iterator<Map.Entry<InetAddress, List<PowerRequest>>> it  = clientMap.entrySet().iterator();
        // Here we need to fast-forward the iterator to get to the priority client
        for (int i = 0; i < priorityClientIndex; i++) {
            it.next();
        }
        for (int i = 0; i < clientMap.size(); i++) {
            Map.Entry<InetAddress, List<PowerRequest>> client = it.next();
            // Is this the priority client?
            if (i == 0) {
                priorityClient = client.getKey();
            }
            int powerGranted = 0;
            if (!client.getValue().isEmpty()) {
                PowerRequest powerRequest = client.getValue().get(0);
                int powerRequested = powerRequest.getPowerRequested();
                powerGranted = powerRequest.getPowerGranted();
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
                }
            }
            // If we're at the end of the list, wrap around to the beginning
            if (!it.hasNext()) {
                it = clientMap.entrySet().iterator();
            }
            log.logGrant(client.getKey(), powerGranted, 0);
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

    public void removeDeniedRequests() {
        for (Map.Entry<InetAddress, List<PowerRequest>> client : clientMap.entrySet()) {
            PowerRequest powerRequest = client.getValue().get(0);
            if (powerRequest != null && powerRequest.getPowerGranted() == 0) {
                // If the request hasn't been granted, remove it from the list
                client.getValue().remove(0);
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
