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
        // TODO: Auto detect broadcast address
        InetAddress localHost = InetAddress.getLocalHost();
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localHost);
        for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
            InetAddress broadcastAddr = interfaceAddress.getBroadcast();
            if (broadcastAddr == null)
                continue;
            System.out.println("Broadcast address is " + broadcastAddr.getHostAddress());
        }
        final String destAddr = "10.0.0.255";
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
    
}
