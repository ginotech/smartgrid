package njit.smartgrid;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.sql.Time;
import java.util.Random;

// IMPORTANT: need to run export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true" or put in ~/.profile
// TODO: add tracking of requests (list of active requests?)

public class PowerClient {

    private static final boolean DEBUG = false;
    private static final boolean HIDE_EMPTY_GRANTS = true;
    private static final boolean RASPBERRY_PI = true;
    private static final int HIGH_POWER_PIN = 7;
    private static final int LOW_POWER_PIN = 12;

    static final int SERVER_PORT = 1234;
    static final int CLIENT_PORT = 1235;
    static final int REQUEST_PACKET_LENGTH = 12;// Size of the request packet in bytes

    private InetAddress myAddr;
    private InetAddress serverAddr;
    private boolean outputState = false;
    private int powerRequested = 0;
    private boolean suppressTerminalOutput = false;
    private long lastRequestTime = 0;
    private long grantPeriod = 0;
    private long minimumRequestSpacing = 0;

    private double p, q;
    private PowerLog log;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.out.println("Usage: java njit.smartgrid.PowerClient <client address> <server address> <on percentage> <cycle length (s)>");
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

        if (args.length < 4) {
            System.out.println("Usage: java njit.smartgrid.PowerClient <client address> <server address> <rho> <cycle length>");
            System.exit(0);
        } else {
            final double rho = Double.parseDouble(args[2]);    // Average on length
            final int cycleLength = Integer.parseInt(args[3]);
            powerClient.calculateProbabilities(rho, cycleLength);
            powerClient.generateRequest();
            powerClient.listenForGrant();
        }
        System.out.println("My address is " + myAddr.getHostAddress());
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
                    // Found our address?
                    if (clientAddr.equals(myAddr)) {
                        if (grantPeriod == 0) {
                            if (minimumRequestSpacing == 0) {
                                minimumRequestSpacing = System.currentTimeMillis();
                            } else {
                                grantPeriod = System.currentTimeMillis() - minimumRequestSpacing;
                                minimumRequestSpacing = (long) (grantPeriod * 0.8);
                            }
                        }
                        // Get the auth values
                        int powerGranted = packetData.getInt();
                        if (powerGranted > 0 || !suppressTerminalOutput) {
                            printTimestamp();
                            System.out.format("Got %dW\n", powerGranted);
                            suppressTerminalOutput = false;
                        }
                        if (powerGranted > 0) {
                            // If we have a nonzero grant, turn on some lights
                            outputState = true;
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
                        } else {
                            // Otherwise, turn off the lights
                            outputState = false;
                            if (HIDE_EMPTY_GRANTS) {
                                suppressTerminalOutput = true;
                            }
                            if (RASPBERRY_PI) {
                                pinWrite(HIGH_POWER_PIN, false);
                                pinWrite(LOW_POWER_PIN, false);
                            }
                        }
                        log.logGrant(myAddr, powerGranted, serverTime);
                        break;
                    } else {
                        packetData.getInt();   // skip the auth fields if they aren't ours
                    }
                }
                generateRequest();
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

    // Send a request packet to the server that contains a timestamp, power level, and # of packets requested
    public void requestPower(int power) {
        lastRequestTime = System.currentTimeMillis();
        if ((power == PowerRequest.POWER_BOTH) || (power == PowerRequest.POWER_HIGH) || (power == PowerRequest.POWER_LOW)) {
            this.powerRequested = power;
        } else {
            this.powerRequested = 0;
        }
        printTimestamp();
        System.out.println("Requesting " + powerRequested + "W");
        log.logRequest(myAddr, powerRequested, 0);
        try (DatagramSocket sendSocket = new DatagramSocket()) {    // New socket on dynamic port
            ByteBuffer requestBuffer = ByteBuffer.allocate(REQUEST_PACKET_LENGTH);
            requestBuffer.putLong(System.currentTimeMillis());  // Timestamp
            requestBuffer.putInt(powerRequested);
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(), REQUEST_PACKET_LENGTH, serverAddr, SERVER_PORT);
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

    private void calculateProbabilities(double rho, int cycleLength) {
        log.logString(String.format("Client: %s, Server: %s", myAddr.getHostAddress(), serverAddr.getHostAddress()));
        log.logString(String.format("Auto generation: Enabled"));
        final double beta = cycleLength * rho;
        final double alpha = cycleLength - beta;
        p = 1.0 / beta;          // Probability ON -> OFF
        q = 1.0 / (alpha + 1.0); // Probability OFF -> ON
        log.logString(String.format("Input values: rho=%f, cycle_length=%d", rho, cycleLength));
        log.logString(String.format("beta=%f, alpha=%f, p=%f, q=%f", beta, alpha, p, q));
    }

    private void generateRequest() {
        // Prevent rapid-fire requests
        if (System.currentTimeMillis() < lastRequestTime + minimumRequestSpacing) {
            return;
        }
        Random rand = new Random();
        double stateChangeRand = rand.nextDouble();
        double powerLevelRand = rand.nextDouble();

        if (DEBUG) { System.out.format("statechange=%f, p=%f, q=%f\n", stateChangeRand, p, q); }

        // Currently on?
        if (outputState) {
            // Turn off?
            if (stateChangeRand <= p) {
                printTimestamp();
                System.out.println("End of request block.");
            } else {
                requestPower(powerRequested);
            }
        } else {
            // Turn on?
            if (stateChangeRand <= q) {
                if (powerLevelRand < 1.0/3.0) {
                    requestPower(PowerRequest.POWER_HIGH);
                } else if (powerLevelRand < 2.0/3.0) {
                    requestPower(PowerRequest.POWER_LOW);
                } else {
                    requestPower(PowerRequest.POWER_BOTH);
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

    private void printTimestamp() {
        Time time = new Time(System.currentTimeMillis());
        System.out.print("[" + time.toString() + "] ");
    }

}
