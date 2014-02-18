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
        final String destAddr = "10.0.0.255";
        final int PORT = 1234;
        int[] authArray = new int[4];
        authArray[0] = 0xDEADBEEF;
        authArray[1] = 0xAABB;
        authArray[2] = 0xCCDDEE;
        authArray[3] = 0xFE;
        DatagramSocket sendSocket = new DatagramSocket(PORT);
        while(true) {
            try {
                PowerPacket packet = new PowerPacket(destAddr, authArray);
                sendSocket.send(packet.getPacket());
                System.out.print('.');
            } catch (UnknownHostException e) {
                System.err.println("Don't know about host " + destAddr);
                System.exit(1);
            } catch (SocketException e) {
                System.err.println("Socket exception " + e.getMessage());
                System.exit(1);
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                System.out.println("derp");
            }
        }
    }
    
}
