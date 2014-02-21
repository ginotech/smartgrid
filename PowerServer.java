package smartgrid;
import smartgrid.PowerPacket;
import java.net.*;
import java.io.IOException;
import java.util.*;

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
       final InetAddress destAddr = getBroadcastAddr(args[0]);
	   Map<InetAddress, Integer> clientAuthMap = new HashMap<InetAddress, Integer>();
       clientAuthMap.put(InetAddress.getByName("10.0.0.11"), 0x0123DEAD);
	   clientAuthMap.put(InetAddress.getByName("10.0.0.12"), 0x0000AABB);
	   clientAuthMap.put(InetAddress.getByName("10.0.0.13"), 0x01CCDDEE);
	   clientAuthMap.put(InetAddress.getByName("10.0.0.14"), 0x000000FE);
       // Create new output socket with dynamically assigned port
       DatagramSocket sendSocket = new DatagramSocket();
       while(true) {
           try {
               PowerPacket packet = new PowerPacket(destAddr, clientAuthMap);
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
