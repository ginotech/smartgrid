package njit.smartgrid;
import java.net.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Time;
import java.util.*;

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
    static final int REQUEST_PACKET_LENGTH = 16;// Size of the request packet in bytes
    static final int TIMESLOT_LENGTH = 5;       // Duration of random requests (in # of grant ticks - must be >1)
    static final int GRANT_PERIOD = 1000;       // How often to expect grant packets from the server (ms)

    private InetAddress myAddr;
    private InetAddress serverAddr;
    private boolean outputState = false;
    private static boolean autoGenerate = false;
    private int packetsRequested = 0;
    private int powerRequested = 0;
    private boolean suppressTerminalOutput = false;

    private double p, q;
    private PowerLog log;

    private boolean pendingRequest = false;

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (TIMESLOT_LENGTH < 2) {
            System.err.println("Invalid TIMESLOT_LENGTH constant!");
        }
        if (args.length < 4) {
            System.out.println("Usage:");
            System.out.println("[manual] java njit.smartgrid.PowerClient <client address> <server address> <power> <# of packets>");
            System.out.println("[auto] java njit.smartgrid.PowerClient <client address> <server address> <auto> <on percentage> <cycle length (s)>");
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
                System.out.println("Usage: java njit.smartgrid.PowerClient <client address> <server address> <auto> <on percentage> <cycle length (s)>");
            } else {
                final double onPercentage = Double.parseDouble(args[3]);    // Average on length
                final double cycleLength = Double.parseDouble(args[4]);
                powerClient.calculateProbabilities(onPercentage, cycleLength);
                powerClient.generateRequest();  // Generate initial request
            }
        } else {
            final int power = Integer.parseInt(args[2]);
            final int numPackets = Integer.parseInt(args[3]);
            powerClient.requestPower(power, numPackets);
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
                        int packetsRemaining = packetData.getInt();
                        if (powerGranted > 0 || !suppressTerminalOutput) {
                            printTimestamp();
                            System.out.format("Received authorization for %dW (%d remaining)\n", powerGranted, packetsRemaining);
                            suppressTerminalOutput = false;
                        }
                        if (packetsRemaining == 1) {
                            pendingRequest = false;
                            if (autoGenerate) {
                                generateRequest();
                            }
                        }
                        if (powerGranted > 0) {
                            outputState = true;
                            packetsRequested--;
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
                            outputState = false;
                            if (HIDE_EMPTY_GRANTS) {
                                suppressTerminalOutput = true;
                            }
                            if (RASPBERRY_PI) {
                                pinWrite(HIGH_POWER_PIN, false);
                                pinWrite(LOW_POWER_PIN, false);
                            }
                            if (!autoGenerate) {
                                printTimestamp();
                                System.out.println("Request satisfied. Exiting.");
                                System.exit(0);
                            }
                        }
                        log.logGrant(myAddr, powerGranted, packetsRemaining, serverTime);
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

    // Send a request packet to the server that contains a timestamp, power level, and # of packets requested
    public void requestPower(int power, int packetsRequested) {
        if ((power == PowerRequest.POWER_BOTH) || (power == PowerRequest.POWER_HIGH) || (power == PowerRequest.POWER_LOW)) {
            this.powerRequested = power;
        } else {
            this.powerRequested = 0;
        }
        this.packetsRequested = packetsRequested;
        printTimestamp();
        System.out.println("Requesting " + powerRequested + "W for " + this.packetsRequested);
        pendingRequest = true;
        try (DatagramSocket sendSocket = new DatagramSocket()) {    // New socket on dynamic port
            ByteBuffer requestBuffer = ByteBuffer.allocate(REQUEST_PACKET_LENGTH);
            requestBuffer.putLong(System.currentTimeMillis());  // Timestamp
            requestBuffer.putInt(powerRequested);
            requestBuffer.putInt(this.packetsRequested);
            DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(), REQUEST_PACKET_LENGTH, serverAddr, SERVER_PORT);
            sendSocket.send(requestPacket);
            log.logRequest(myAddr, powerRequested, this.packetsRequested, 0);
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

    private void calculateProbabilities(double onPercentage, double cycleLength) {
        log.logString(String.format("Client: %s, Server: %s", myAddr.getHostAddress(), serverAddr.getHostAddress()));
        log.logString(String.format("Timeslot length: %d, Grant period: %dms", TIMESLOT_LENGTH, GRANT_PERIOD));
        if (autoGenerate) {
            log.logString(String.format("Auto generation: Enabled"));
        } else {
            log.logString(String.format("Auto generation: Disabled"));
        }
        final double beta = cycleLength / TIMESLOT_LENGTH * onPercentage;
        final double alpha = cycleLength / TIMESLOT_LENGTH - beta;
        p = 1.0 / beta;          // Probability ON -> OFF
        q = 1.0 / (alpha + 1.0); // Probability OFF -> ON
        autoGenerate = true;
        log.logString(String.format("beta=%f, alpha=%f, p=%f, q=%f", beta, alpha, p, q));
    }

    private void generateRequest() {
        if (pendingRequest) { return; }
        Random rand = new Random();
        double stateChangeRand = rand.nextDouble();
        double powerLevelRand = rand.nextDouble();

        if (DEBUG) { System.out.format("statechange=%f, p=%f, q=%f\n", stateChangeRand, p, q); }

        // Currently on?
        if (outputState) {
            // Turn off?
            if (stateChangeRand <= p) {
                printTimestamp();
                System.out.println("End of request.");
                scheduleRequestGeneration();
            } else {
                requestPower(powerRequested, TIMESLOT_LENGTH);
                printTimestamp();
                System.out.println("Continuing request");
            }
        } else {
            // Turn on?
            if (stateChangeRand <= q) {
                if (powerLevelRand < 1.0/3.0) {
                    requestPower(PowerRequest.POWER_HIGH, TIMESLOT_LENGTH);
                } else if (powerLevelRand < 2.0/3.0) {
                    requestPower(PowerRequest.POWER_LOW, TIMESLOT_LENGTH);
                } else {
                    requestPower(PowerRequest.POWER_BOTH, TIMESLOT_LENGTH);
                }
            } else {
                scheduleRequestGeneration();
            }
        }
    }

    private void scheduleRequestGeneration() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                generateRequest();
            }
        }, TIMESLOT_LENGTH * GRANT_PERIOD);
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
