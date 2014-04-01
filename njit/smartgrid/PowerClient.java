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

    static final boolean RASPBERRY_PI = false;

    static final int SERVER_PORT = 1234;
    static final int CLIENT_PORT = 1235;
    static final int REQUEST_PACKET_LENGTH = 12; // Size of the request packet in bytes
    static InetAddress myAddr;
    static int powerRequested = 0;
    static PowerLog log;

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
        powerRequested = Integer.parseInt(args[2]);
        System.out.println("My address is " + myAddr.getHostAddress());
        log = new PowerLog(false);
        requestPower(serverAddr);
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
                long serverTime = packetData.getLong();
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
                    if (authValue > 0) {
                        Time time = new Time(System.currentTimeMillis());
                        System.out.print("[" + time.toString() + "] ");
                        System.out.format("Received authorization for 0x%08X (%d)\n", authValue, authValue);
                        powerRequested--;
                        if (RASPBERRY_PI) {
                            Process p = Runtime.getRuntime().exec("gpio mode 11 output");
                            Process q = Runtime.getRuntime().exec("gpio write 11 1");
                        }
                        log.logGrant(myAddr, authValue, serverTime);
                    } else {
                        if (RASPBERRY_PI) {
                            Process r = Runtime.getRuntime().exec("gpio write 11 0");
                        }
                        if (powerRequested == 0) {
                            System.out.println("Request satisfied. Exiting.");
                            System.exit(0);
                        }
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

    // Send a request packet to the server that contains a timestamp and the amount of power requested
    public static void requestPower(InetAddress serverAddr) {
        System.out.println("Sending power request for " + powerRequested);
        try (DatagramSocket sendSocket = new DatagramSocket()) {    // New socket on dynamic port
            ByteBuffer requestBuffer = ByteBuffer.allocate(REQUEST_PACKET_LENGTH);
            requestBuffer.putLong(System.currentTimeMillis());  // Timestamp
            requestBuffer.putInt(powerRequested);               // Power requested
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(), REQUEST_PACKET_LENGTH, serverAddr, SERVER_PORT);
            sendSocket.send(requestPacket);
            log.logRequest(myAddr, 0, powerRequested, 0);
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
