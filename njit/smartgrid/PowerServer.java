package smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;

public class PowerServer {
    
    static final int GRANT_FREQUENCY = 3000;    // How often to send grant packets (milliseconds)
    static final int LISTEN_PORT = 1234;        // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 4; // Size of the request packet in bytes
    static InetAddress myAddr = null;
    static InetAddress destAddr = null;
    static Map<InetAddress, Integer> clientAuthMap = new HashMap<>();
    // ^ Should this be here, or in main()?

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java smartgrid.PowerServer <interface>");
            System.out.println("IMPORTANT: export _JAVA_OPTIONS=\"-Djava.net.preferIPv4Stack=true\"");
            System.exit(0);
        }
        final String intf = args[0];
        myAddr = getMyAddr(intf);
        destAddr = getBroadcastAddr(intf);
        
        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
        // This should execute in a background thread and not affect listenForRequest()
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                sendGrantPacket();  // Send grant broadcast
                updateAuthMap();    // Decrement auth quantities and remove if no longer active
            }
        }, GRANT_FREQUENCY, GRANT_FREQUENCY);
        listenForRequest();
    }
    
    // Wait for an authorization request from a client
    public static void listenForRequest() {
        try (DatagramSocket receiveSocket = new DatagramSocket(LISTEN_PORT)) {
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
                    // TODO: Validate the request packet before doing anything with it!
                    int powerRequested = ByteBuffer.wrap(packet.getData()).getInt();
                    System.out.format("Request from %s for %d: ", clientAddr, powerRequested);
                    if (authorizeRequest(clientAddr, powerRequested)) {
                        System.out.println("Authorized.");
                    }
                    else {
                        System.out.println("Rejected.");
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
        // TODO: Implement useful authorization logic
        // For now, just authorize all valid requests and add to clientAuthMap
        if (powerRequested > 0) {
            clientAuthMap.put(clientAddr, powerRequested);
            return true;
        }
        return false;
    }
    
    // Decrement authorization amounts and remove if zero
    // NOTE: Right now, a grant packet with an auth quantity of zero will be sent before the entry is removed.
    // Unsure if this behavior is desired. Easy change though.
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
//            System.out.print("Nothing to send.");
            return;
        }
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerPacket packet = new PowerPacket(destAddr, clientAuthMap);
            sendSocket.send(packet.getPacket());
            System.out.println("Sent grant packet.");
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

    public static InetAddress getMyAddr(String intf) {
        try {
            NetworkInterface networkInterface;
            // Try to find target interface
            networkInterface = NetworkInterface.getByName(intf);
            // If none is found, error out
            if (networkInterface == null) {
                System.err.println("Invalid network interface.");
                System.exit(1);
            }
            // Iterate through the bound addresses and return the first real addr
            for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                InetAddress myAddr = interfaceAddress.getAddress();
                if (myAddr != null) {
                    return myAddr;
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket exception " + e.getMessage());
            System.exit(1);
        }
        System.err.println("Unable to get address!");
        System.exit(1);
        return null;
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
