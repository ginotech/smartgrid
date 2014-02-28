package smartgrid;
import smartgrid.PowerPacket;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;

public class PowerServer {
    
    static final int GRANT_FREQUENCY = 3000;    // How often to send grant packets (milliseconds)
    static final int LISTEN_PORT = 1234;        // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 4; // Size of the request packet in bytes
    static InetAddress destAddr;
    static Map<InetAddress, Integer> clientAuthMap = new HashMap<>();
    // ^ Should this be here, or in main()?

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java smartgrid.PowerServer <interface>");
            System.exit(0);
        }
        destAddr = getBroadcastAddr(args[0]);
//        clientAuthMap.put(InetAddress.getByName("10.0.0.11"), 0x0123DEAD);
//        clientAuthMap.put(InetAddress.getByName("10.0.0.12"), 0x0000AABB);
//        clientAuthMap.put(InetAddress.getByName("10.0.0.13"), 0x01CCDDEE);
//        clientAuthMap.put(InetAddress.getByName("10.0.0.14"), 0x000000FE);
        
        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
        // This should execute in a background thread and not effect listenForRequest()
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                sendGrantPacket();
            }
        }, GRANT_FREQUENCY, GRANT_FREQUENCY);
        while (true) {
            listenForRequest();
        }
    }
    
    // Wait for an authorization request from a client
    public static void listenForRequest() {
        DatagramSocket receiveSocket;
        byte[] packetDataArray = new byte[REQUEST_PACKET_LENGTH];
        try {
            receiveSocket = new DatagramSocket(LISTEN_PORT);
            DatagramPacket packet = new DatagramPacket(packetDataArray, REQUEST_PACKET_LENGTH);
            receiveSocket.receive(packet);
            InetAddress clientAddr = packet.getAddress();
            int powerRequested = ByteBuffer.wrap(packet.getData()).getInt();
            System.out.format("Request from %s for %i", clientAddr, powerRequested);
            if (authorizeRequest(clientAddr, powerRequested)) {
                System.out.println("Authorized.");
            }
            else {
                System.out.println("Rejected.");
            }
        } catch (SocketException e) {
            System.err.println("SocketException");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("IOException");
            System.exit(1);
        }
    }
    
    // Decide if we want to authorize a power request, and for how much
    public static boolean authorizeRequest(InetAddress clientAddr, int powerRequested) {
        // TODO: Implement useful authorization logic
        // For now, just authorize all valid requests and add to clientAuthMap
        if (powerRequested > 0) {
            clientAuthMap.put(clientAddr, powerRequested);
            return true;
        }
        return false;
    }
    
    // Decrement authorization amounts and remove if zero
    public static void updateAuthMap() {
        for (Iterator<Map.Entry<InetAddress, Integer>> i = clientAuthMap.entrySet().iterator(); i.hasNext();) {
            // If authorized power amount is zero, remove the entry from the map
            Map.Entry<InetAddress, Integer> entry = i.next();
            int powerRequested = entry.getValue();
            if (powerRequested == 0) {
                i.remove();
            }
            // Otherwise, decrement by 1
            else {
                entry.setValue(powerRequested - 1);
            }
        }
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public static void sendGrantPacket() {
        // If there are no grants to be sent, don't do anything
        if (clientAuthMap.isEmpty()) {
            return;
        }
        // Create new output socket with dynamically assigned port
        DatagramSocket sendSocket;
        try {
            sendSocket = new DatagramSocket();
            PowerPacket packet = new PowerPacket(destAddr, clientAuthMap);
            sendSocket.send(packet.getPacket());
            System.out.println("Sent request packet.");
        } catch (SocketException e) {
            System.err.println("Socket exception " + e.getMessage());
            System.exit(1);
        } catch (UnknownHostException e) {
            System.err.println("UnknownHostException");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("IOException");
            System.exit(1);
        }
    }
    
    public static InetAddress getBroadcastAddr(String intf) {
        try {
            NetworkInterface networkInterface;
            // Try to find target interface
            networkInterface = NetworkInterface.getByName(intf);
            // If none is found, error out
            if (networkInterface == null) {
                System.err.println("Invalid network interface.");
                System.exit(1);
            }
            // Iterate through the bound addresses and return the first broadcast addr
            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress broadcastAddr = interfaceAddress.getBroadcast();
                if (broadcastAddr != null) {
                    System.out.println("Broadcasting to " + broadcastAddr.getHostAddress());
                    return broadcastAddr;
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket exception " + e.getMessage());
            System.exit(1);
        }
        return null;
    }
}
