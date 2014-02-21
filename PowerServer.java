package smartgrid;
import smartgrid.PowerPacket;
import java.net.*;
import java.io.IOException;

public class PowerServer {
    
    static final int GRANT_FREQUENCY = 3000;    // How often to send grant packets (milliseconds)

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
			System.out.println("Usage: PowerServer <interface>");
			System.exit(0);
		}
       final String destAddr = getBroadcastAddr(args[0]);
       int[] authArray = new int[4];
       authArray[0] = 0xDEADBEEF;
       authArray[1] = 0xAABB;
       authArray[2] = 0xCCDDEE;
       authArray[3] = 0xFE;
       // Create new output socket with dynamically assigned port
       DatagramSocket sendSocket = new DatagramSocket();
       while(true) {
           try {
               PowerPacket packet = new PowerPacket(destAddr, authArray);
               sendSocket.send(packet.getPacket());
               System.out.print('.');
           } catch (UnknownHostException e) {
               System.err.println("Unknown host " + destAddr);
               System.exit(1);
           } catch (SocketException e) {
               System.err.println("Socket exception " + e.getMessage());
               System.exit(1);
           }
           try {
               Thread.sleep(GRANT_FREQUENCY);
           } catch (InterruptedException e) {
               System.out.println("InterruptedException");
           }
       }
    }
    
    public static String getBroadcastAddr(String intf) {
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
                    return broadcastAddr.getHostAddress();
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket exception " + e.getMessage());
            System.exit(1);
        }
        return null;
    } 
}
