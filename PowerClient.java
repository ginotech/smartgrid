package smartgrid;
import smartgrid.PowerPacket;
import java.net.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

// IMPORTANT: need to run export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true" or put in ~/.profile


public class PowerClient {
    
    static final int LISTEN_PORT = 1234;
    static InetAddress myAddr;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: PowerClient <interface> <server address> <request size>");
            System.out.println("IMPORTANT: export _JAVA_OPTIONS=\"-Djava.net.preferIPv4Stack=true\"");
            System.exit(0);
        }
        myAddr = getMyAddr(args[0]);
        final String serverAddr = args[1];
        final int powerRequested = Integer.parseInt(args[2]);
        System.out.println("My address is " + myAddr.getHostAddress());
        requestPower(serverAddr, powerRequested);
        listenForGrant();
    }
    
    public static void listenForGrant() {
        try (DatagramSocket receiveSocket = new DatagramSocket(LISTEN_PORT)) {
            while (true) {
                PowerPacket packet = new PowerPacket();
                receiveSocket.receive(packet.getPacket());
                ByteBuffer packetData = ByteBuffer.wrap(packet.getData());
                // Build the packet back into a map of address and auth values
                Map<InetAddress, Integer> clientAuthMap = new HashMap<>();
                packetData.get();   // Need to throw away first 0xFF byte
                while (packetData.remaining() >= 8) {
                    // Get the address
                    byte[] addrArray = new byte[4];
                    packetData.get(addrArray, 0, 4);
                    // FIXME: does not break out of loop at stop bytes
                    // If we hit the stop bytes, break out of loop
                    if (addrArray[0] == (byte)0xFF) {
                        break;
                    }
                    InetAddress clientAddr = InetAddress.getByAddress(addrArray);
                    // Get the value
                    int authValue = packetData.getInt();
                    // Add address and value to the map
                    clientAuthMap.put(clientAddr, authValue);
                }
                if (clientAuthMap.containsKey(myAddr)) {
                    int authValue = clientAuthMap.get(myAddr);
                    System.out.format("Received authorization for 0x%08X (%d)\n", authValue, authValue);
                    if (authValue == 0) {
                        System.exit(0);
                    }
                }
            }
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

    public static void requestPower(String serverAddr, int powerRequested) {
        try (DatagramSocket sendSocket = new DatagramSocket()) {    // New socket on dynamic port
            ByteBuffer requestBuffer = ByteBuffer.allocate(4);
            requestBuffer.putInt(powerRequested);
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(), 4, InetAddress.getByName(serverAddr), LISTEN_PORT);
            sendSocket.send(requestPacket);
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
}
