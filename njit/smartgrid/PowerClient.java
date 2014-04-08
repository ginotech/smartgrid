package njit.smartgrid;
import java.net.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Time;
import java.util.*;

// IMPORTANT: need to run export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true" or put in ~/.profile
// TODO: add tracking of requests (list of active requests?)

public class PowerClient {

    private static final boolean DEBUG = true;

    private static final boolean RASPBERRY_PI = true;
    private static final int HIGH_POWER_PIN = 7;
    private static final int LOW_POWER_PIN = 12;

    static final int SERVER_PORT = 1234;
    static final int CLIENT_PORT = 1235;
    static final int REQUEST_PACKET_LENGTH = 16; // Size of the request packet in bytes
    static final int REQUEST_DURATION = 5;      // Duration of random requests (seconds)

    private InetAddress myAddr;
    private InetAddress serverAddr;
    private boolean outputEnabled = false;
    private static boolean autoGenerate = false;
    private int durationRequested = 0;
    private int powerRequested = 0;
    private PowerLog log;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.out.println("Usage:");
            System.out.println("[manual] java njit.smartgrid.PowerClient <client address> <server address> <power> <duration>");
            System.out.println("[auto] java njit.smartgrid.PowerClient <client address> <server address> <auto> <on> <off>");
            System.exit(0);
        }
        final PowerClient powerClient;
        final InetAddress myAddr = InetAddress.getByName(args[0]);
        final InetAddress serverAddr = InetAddress.getByName(args[1]);
        powerClient = new PowerClient(myAddr, serverAddr);

        if (RASPBERRY_PI) {
            Runtime.getRuntime().exec("gpio mode " + HIGH_POWER_PIN + " output");
            Runtime.getRuntime().exec("gpio mode " + LOW_POWER_PIN + " output");
            powerClient.pinWrite(HIGH_POWER_PIN, false);
            powerClient.pinWrite(LOW_POWER_PIN, false);
        }

        if (args[2].equals("auto")) {
            if (args.length < 5) {
                System.out.println("Usage: java njit.smartgrid.PowerClient <client address> <server address> <auto> <on> <off>");
            } else {
                final double beta = Double.parseDouble(args[3]);    // Average on time in seconds
                final double alpha = Double.parseDouble(args[4]);   // Average off time in seconds
                autoGenerate = true;
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        powerClient.generateRequest(alpha, beta);
                    }
                }, 0, REQUEST_DURATION * 1000);
            }
        } else {
            final int power = Integer.parseInt(args[2]);
            final int duration = Integer.parseInt(args[3]);
            powerClient.requestPower(power, duration);
        }

        System.out.println("My address is " + myAddr.getHostAddress());
        powerClient.listenForGrant();
    }

    public PowerClient(InetAddress myAddr, InetAddress serverAddr) {
        this.myAddr = myAddr;
        this.serverAddr = serverAddr;
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
                            outputEnabled = true;
                            if (RASPBERRY_PI) {
                                if (powerGranted == PowerRequest.POWER_HIGH) {
                                    pinWrite(HIGH_POWER_PIN, true);
                                    pinWrite(LOW_POWER_PIN, false);
                                } else if (powerGranted == PowerRequest.POWER_LOW) {
                                    pinWrite(LOW_POWER_PIN, true);
                                    pinWrite(HIGH_POWER_PIN, false);
                                } else if (powerGranted == PowerRequest.POWER_BOTH) {
                                    pinWrite(HIGH_POWER_PIN, true);
                                    pinWrite(LOW_POWER_PIN, true);
                                } else {
                                    System.err.println("Invalid grant amount.");
                                }
                            }
                            log.logGrant(myAddr, powerGranted, durationGranted, serverTime);
                        } else {
                            if (durationRequested == 0) {
                                outputEnabled = false;
                                if (RASPBERRY_PI) {
                                    pinWrite(HIGH_POWER_PIN, false);
                                    pinWrite(LOW_POWER_PIN, false);
                                }
                                if (!autoGenerate) {
                                    System.out.println("Request satisfied. Exiting.");
                                    System.exit(0);
                                }
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
    public void requestPower(int power, int duration) {
        if ((power == PowerRequest.POWER_BOTH) || (power == PowerRequest.POWER_HIGH) || (power == PowerRequest.POWER_LOW)) {
            this.powerRequested = power;
        } else {
            this.powerRequested = 0;
        }
        this.durationRequested = duration;
        System.out.println("Requesting " + powerRequested + "W for " + durationRequested + "s");
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

    private void generateRequest(double alpha, double beta) {
        double p = 1.0 / beta;          // Probability ON -> OFF
        double q = 1.0 / (alpha + 1.0); // Probability OFF -> ON
        Random rand = new Random();
        double stateChangeRand = rand.nextDouble();
        double powerLevelRand = rand.nextDouble();

        if (DEBUG) { System.out.format("statechange=%f, p=%f, q=%f\n", stateChangeRand, p, q); }

        if (outputEnabled) {
            // Stay on?
            if ((1-p) >= stateChangeRand) {
                requestPower(powerRequested, REQUEST_DURATION);
            }
        } else {
            // Turn on?
            if (q >= stateChangeRand) {
                // TODO: Add POWER_BOTH generate option
                if (powerLevelRand < 1.0/3.0) {
                    requestPower(PowerRequest.POWER_HIGH, REQUEST_DURATION);
                } else if (powerLevelRand < 2.0/3.0) {
                    requestPower(PowerRequest.POWER_LOW, REQUEST_DURATION);
                } else {
                    requestPower(PowerRequest.POWER_BOTH, REQUEST_DURATION);
                }
            }
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
