package njit.smartgrid;
import java.net.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Time;
import java.util.*;

// IMPORTANT: need to run export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true" or put in ~/.profile
// TODO: Add random request generation
// TODO: add tracking of requests

public class PowerClient {

    static final int SERVER_PORT = 1234;
    static final int CLIENT_PORT = 1235;
    static InetAddress myAddr;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage: java njit.smartgrid.PowerClient <client address> <server address> <request size>");
            if (System.getProperty("os.name").contains("Linux")) {
                System.out.println("IMPORTANT: export _JAVA_OPTIONS=\"-Djava.net.preferIPv4Stack=true\"");
            }
            System.exit(0);
        }
        myAddr = InetAddress.getByName(args[0]);
        final InetAddress serverAddr = InetAddress.getByName(args[1]);
        final int powerRequested = Integer.parseInt(args[2]);
        System.out.println("My address is " + myAddr.getHostAddress());
        requestPower(serverAddr, powerRequested);
        listenForGrant();
    }
    
    public static void listenForGrant() {
        try (DatagramSocket receiveSocket = new DatagramSocket(CLIENT_PORT)) {
            while (true) {
                PowerGrantPacket packet = new PowerGrantPacket();
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
                    Time time = new Time(System.currentTimeMillis());
                    System.out.print("[" + time.toString() + "] ");
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

    public static void requestPower(InetAddress serverAddr, int powerRequested) {
        System.out.println("Sending power request for " + powerRequested);
        try (DatagramSocket sendSocket = new DatagramSocket()) {    // New socket on dynamic port
            ByteBuffer requestBuffer = ByteBuffer.allocate(4);
            requestBuffer.putInt(powerRequested);
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(), 4, serverAddr, SERVER_PORT);
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
}