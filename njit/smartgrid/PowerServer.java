package smartgrid;
import java.net.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.sql.Time;
import java.util.*;

// TODO: Actually instantiate the PowerServer class and un-static everything for god's sake!
public class PowerServer {
    
    static final int GRANT_FREQUENCY = 500;     // How often to send grant packets (milliseconds)
    static final int PORT = 1234;               // Port on which to listen for requests / destination port for grants
    static final int REQUEST_PACKET_LENGTH = 4; // Size of the request packet in bytes

    static final int SCHEDULER_LIMIT        = 0;
    static final int SCHEDULER_FCFS         = 1;
    static final int SCHEDULER_ROUNDROBIN   = 2;

    static InetAddress myAddr = null;
    static InetAddress destAddr = null;
    static int maxLoad;
    static int currentLoad = 0;
    static int schedulerType = SCHEDULER_LIMIT; // Default
    static int quantum = 0;
    static Queue<PowerRequest> clientsActive;
    static Queue<PowerRequest> clientsWaiting;

    // ^ Should this be here, or in main()?

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: java smartgrid.PowerServer <interface> <capacity> [scheduler] [scheduler option]");
            System.out.println("IMPORTANT: export _JAVA_OPTIONS=\"-Djava.net.preferIPv4Stack=true\"");
            System.exit(0);
        }
        final String intf = args[0];
        maxLoad = Integer.parseInt(args[1]);
        if (args.length >= 3) {
            switch (args[2].toLowerCase()) {
                case "limit":
                    schedulerType = SCHEDULER_LIMIT;
                    break;
                case "fcfs":
                    schedulerType = SCHEDULER_FCFS;
                    break;
                case "roundrobin": case "rr":
                    if (args.length < 4) {
                        System.out.println("To use the round-robin scheduler you must specify the quantum.");
                        System.exit(0);
                    }
                    schedulerType = SCHEDULER_ROUNDROBIN;
                    quantum = Integer.parseInt(args[4]);
                    break;
                default:
                    schedulerType = SCHEDULER_LIMIT;
                    break;
            }
        }
        myAddr = getMyAddr(intf);
        destAddr = getBroadcastAddr(intf);
        System.out.println("Broadcasting to " + destAddr.getHostAddress());
        System.out.println("Capacity: " + maxLoad);

        clientsActive = new LinkedList<PowerRequest>();
        clientsWaiting = new LinkedList<PowerRequest>();
        
        // Sends a grant packet every GRANT_FREQUENCY (ms) comprised of all
        // requests since last grand packet was sent
        // This should execute in a background thread and not affect listenForRequest()
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                sendGrantPacket();  // Send grant broadcast
                updateAuthQueue();    // Decrement auth quantities and remove if no longer active
            }
        }, GRANT_FREQUENCY, GRANT_FREQUENCY);
        listenForRequest();
    }
    
    // Wait for an authorization request from a client
    public static void listenForRequest() {
        try (DatagramSocket receiveSocket = new DatagramSocket(PORT)) {
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
                    printTimestamp();
                    System.out.format("Request from %s for %d: ", clientAddr, powerRequested);
                    if (authorizeRequest(clientAddr, powerRequested)) {
                        System.out.println("Authorized.");
                    } else {
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
        if (powerRequested > 0) {
            PowerRequest clientRequest = new PowerRequest(clientAddr, powerRequested);
            if (currentLoad < maxLoad) {    // Do we have at least one slot open?
                clientsActive.add(clientRequest);
                currentLoad++;                                  // Increment the number of active clients
                return true;
            } else {  // If we're maxed out, add the requesting client to a list to be served later
                switch (schedulerType) {
                    case 0:
                        return false;
                    case 1:
                        break;
                    case 2:
                        break;
                    default:
                        break;
                }
            }
        }
        return false;
    }

    // Decrement authorization amounts and remove if zero
    public static void updateAuthQueue() {
        for (Iterator<PowerRequest> i = clientsActive.iterator(); i.hasNext();) {
            // If authorized power amount is zero, remove the entry from the map
            PowerRequest entry = i.next();
            int powerRequested = entry.getPowerRequested();
            if (powerRequested == 0) {
                i.remove();
                currentLoad--;
            } else {
                entry.setPowerRequested(powerRequested - 1);
            }
        }
    }
    
    // Send a broadcast packet with client addresses and authorization amounts
    public static void sendGrantPacket() {
        // If there are no grants to be sent, don't do anything
        if (clientsActive.isEmpty()) {
            return;
        }
        // Create new output socket with dynamically assigned port
        try (DatagramSocket sendSocket = new DatagramSocket()) {
            PowerPacket packet = new PowerPacket(destAddr, clientsActive);
            sendSocket.send(packet.getPacket());
            printTimestamp();
            System.out.println("Sent grant packet. System load: " + currentLoad + "/" + maxLoad);
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
            System.err.println(e.toString());
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
                    return broadcastAddr;
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket exception " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    private static void printTimestamp() {
        Time time = new Time(System.currentTimeMillis());
        System.out.print("[" + time.toString() + "] ");
    }
}
