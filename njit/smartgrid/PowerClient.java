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

    private static final boolean RASPBERRY_PI = false;
    private static final int HIGH_POWER_PIN = 7;
    private static final int LOW_POWER_PIN = 12;
    static final int SERVER_PORT = 1234;
    static final int CLIENT_PORT = 1235;
    static final int REQUEST_PACKET_LENGTH = 16; // Size of the request packet in bytes

    private InetAddress myAddr;
    private int durationRequested = 0;
    private int powerRequested = 0;
    private PowerLog log;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.out.println("Usage: java njit.smartgrid.PowerClient <client address> <server address> <power> <duration>");
            System.exit(0);
        }
        final InetAddress myAddr = InetAddress.getByName(args[0]);
        final InetAddress serverAddr = InetAddress.getByName(args[1]);
        final int powerRequested = Integer.parseInt(args[2]);
        final int durationRequested = Integer.parseInt(args[3]);
        System.out.println("My address is " + myAddr.getHostAddress());

        if (RASPBERRY_PI) {
            Runtime.getRuntime().exec("gpio mode " + HIGH_POWER_PIN + " output");
            Runtime.getRuntime().exec("gpio mode " + LOW_POWER_PIN + " output");
        }

        PowerClient powerClient = new PowerClient(myAddr, durationRequested, powerRequested);
        powerClient.requestPower(serverAddr);
        powerClient.listenForGrant();
    }

    public PowerClient(InetAddress myAddr, int durationRequested, int powerRequested) {
        this.myAddr = myAddr;
        this.durationRequested = durationRequested;
        this.powerRequested = powerRequested;

        this.log = new PowerLog(false);
    }

    public void listenForGrant() {
        try (DatagramSocket receiveSocket = new DatagramSocket(CLIENT_PORT)) {
            while (true) {
                PowerGrantPacket packet = new PowerGrantPacket();
                receiveSocket.receive(packet.getPacket());
                ByteBuffer packetData = ByteBuffer.wrap(packet.getData());
                // Build the packet back into a map of address and auth values
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
                    if (clientAddr.equals(myAddr)) {
                        // Get the auth values
                        int powerGranted = packetData.getInt();
                        int durationGranted = packetData.getInt();
                        if (durationGranted > 0) {
                            Time time = new Time(System.currentTimeMillis());
                            System.out.print("[" + time.toString() + "] ");
                            System.out.format("Received authorization for %dW (%ds remaining)\n", powerGranted, durationGranted - 1);
                            durationRequested--;
                            if (RASPBERRY_PI) {
//                            Process gpio_on = Runtime.getRuntime().exec("gpio write " + HIGH_POWER_PIN + " 1");
                                if (powerGranted == PowerRequest.HIGH_POWER_WATTS) {
                                    pinWrite(HIGH_POWER_PIN, true);
                                } else {
                                    pinWrite(LOW_POWER_PIN, true);
                                }
                            }
                            log.logGrant(myAddr, powerGranted, serverTime);
                        } else {
                            if (RASPBERRY_PI) {
//                            Process gpio_off = Runtime.getRuntime().exec("gpio write " + HIGH_POWER_PIN + " 0");
                                pinWrite(HIGH_POWER_PIN, false);
                                pinWrite(LOW_POWER_PIN, false);
                            }
                            if (durationRequested == 0) {
                                System.out.println("Request satisfied. Exiting.");
                                System.exit(0);
                            }
                        }
                        break;
                    } else {
                        packetData.getLong();   // skip the auth fields if they aren't ours
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

    // Send a request packet to the server that contains a timestamp and the duration of power requested
    public void requestPower(InetAddress serverAddr) {
        System.out.println("Sending power request for " + durationRequested + " to " + serverAddr.getHostAddress());
        try (DatagramSocket sendSocket = new DatagramSocket()) {    // New socket on dynamic port
            ByteBuffer requestBuffer = ByteBuffer.allocate(REQUEST_PACKET_LENGTH);
            requestBuffer.putLong(System.currentTimeMillis());  // Timestamp
            requestBuffer.putInt(powerRequested);
            requestBuffer.putInt(durationRequested);
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(), REQUEST_PACKET_LENGTH, serverAddr, SERVER_PORT);
            sendSocket.send(requestPacket);
            log.logRequest(myAddr, powerRequested, durationRequested, 0);
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

    private void pinWrite(int pin, boolean state) {
        try {
            if (state) {
                Runtime.getRuntime().exec("gpio write " + pin + " 1");
            } else {
                Runtime.getRuntime().exec("gpio write " + pin + " 0");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
