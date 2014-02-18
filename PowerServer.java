package smartgrid;
import smartgrid.PowerPacket;
import java.net.DatagramSocket;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.io.IOException;

public class PowerServer {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        String destAddr = "128.235.203.255";
        int[] authArray = new int[4];
        authArray[0] = 0xDEADBEEF;
        authArray[1] = 0xAABB;
        authArray[2] = 0xCCDDEE;
        authArray[3] = 0xFE;
        try {
            PowerPacket packet = new PowerPacket(destAddr, authArray);
            DatagramSocket sendSocket = new DatagramSocket(packet.getPort());
            sendSocket.send(packet.getPacket());
        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + destAddr);
            System.exit(1);
        } catch (SocketException e) {
            System.exit(1);
        }
    }
    
}
