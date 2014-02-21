package smartgrid;
import smartgrid.PowerPacket;
import java.net.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

// IMPORTANT: need to run export _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true" or put in ~/.profile


public class PowerClient {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
			System.out.println("Usage: PowerClient <interface>");
			System.exit(0);
		}
        InetAddress myAddr = getMyAddr(args[0]);
		if (myAddr == null) {
			System.err.println("Couldn't get address for interface " + args[0]);
			System.exit(1);
		}
		System.out.println("My address is " + myAddr.getHostAddress());
        try {
            DatagramSocket receiveSocket = new DatagramSocket(1234);
            while(true) {
                PowerPacket packet = new PowerPacket();
                receiveSocket.receive(packet.getPacket());
                ByteBuffer packetData = ByteBuffer.wrap(packet.getData());
                // Build the packet back into a map of address and auth values
                Map<InetAddress, Integer> clientAuthMap = new HashMap<InetAddress, Integer>();
                packetData.get();   // Need to throw away first 0xFF byte
                while (packetData.remaining() >= 8) {
                    // Get the address
                    byte[] addrArray = new byte[4];
                    packetData.get(addrArray, 0, 4);
                    // FIXME: does not break out of loop at stop bytes
                    // If we hit the stop bytes, break out of loop
                    if (addrArray[0] == 0xFF) {
                        break;
                    }
                    InetAddress clientAddr = InetAddress.getByAddress(addrArray);
                    // Get the value
                    int authValue = packetData.getInt();
                    // Add address and value to the map
                    clientAuthMap.put(clientAddr, authValue);
                }
                if (clientAuthMap.containsKey(myAddr)) {
                        System.out.format("Received authorization for 0x%08X\n", clientAuthMap.get(myAddr));
                }
            }
        } catch (UnknownHostException e) {
            
        } catch (SocketException e) {
            
        }
    }
    //
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
        return null;
    }
}
