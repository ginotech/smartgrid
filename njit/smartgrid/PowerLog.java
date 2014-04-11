package njit.smartgrid;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PowerLog {

    private static final String DELIMITER = ", ";
    private static final boolean HUMAN_READABLE = true;

    private boolean isServer = false;
    private FileWriter fw = null;
    private BufferedWriter bw = null;

    // Constructor (server log)
    public PowerLog(boolean isServer) {
        this.isServer = isServer;
        Date date = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        String fileName;
        if (isServer) {
            fileName = "server_";
        }
        else {
            fileName = "client_";
        }
        fileName += dateFormat.format(date) + ".log";
        try {
            fw = new FileWriter(fileName);
            bw = new BufferedWriter(fw);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });

    }

    public void logString(String msg) {
        try {
            bw.write(msg, 0, msg.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logRequest(InetAddress clientAddress, int powerRequested, int packetsRequested,
                           long clientTimestamp) {
        String logString = "REQ" + DELIMITER;
        if (HUMAN_READABLE) {
            String time = new Timestamp(System.currentTimeMillis()).toString();
            while (time.length() < 23) {
                time += "0";
            }
            logString += time + DELIMITER;   // Local timestamp (server or client)
        } else {
            logString += System.currentTimeMillis() + DELIMITER;
        }
        if (isServer) {
            if (HUMAN_READABLE) {
                String clientRequestTime = new Timestamp(clientTimestamp).toString();
                while (clientRequestTime.length() < 23) {
                    clientRequestTime += "0";
                }
                logString += clientRequestTime + DELIMITER;  // Client timestamp (if server)
            } else {
                logString += clientTimestamp + DELIMITER;
            }

        }
        logString += clientAddress.getHostAddress() + DELIMITER; // Client IP address
        logString += powerRequested + "W" + DELIMITER;           // Power requested
        logString += packetsRequested;                           // Number of packets requested

        try {
            bw.write(logString, 0, logString.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logGrant(InetAddress clientAddress, int powerGranted, int packetsRemaining, long serverTimestamp) {
        String logString = "GRA" + DELIMITER;
        if (HUMAN_READABLE) {
            String time = new Timestamp(System.currentTimeMillis()).toString();
            while (time.length() < 23) {
                time += "0";
            }
            logString += time + DELIMITER;   // Local timestamp (server or client)
        } else {
            logString += System.currentTimeMillis() + DELIMITER;
        }
        if (!isServer) {
            if (HUMAN_READABLE) {
                String serverGrantTime = new Timestamp(serverTimestamp).toString();
                while (serverGrantTime.length() < 23) {
                    serverGrantTime += "0";
                }
                logString += serverGrantTime + DELIMITER;   // Server timestamp (if client)
            } else {
                logString += serverTimestamp + DELIMITER;
            }
        }
        logString += clientAddress.getHostAddress() + DELIMITER;
        logString += powerGranted + "W";
        if (!isServer) {
            logString += DELIMITER + packetsRemaining;
        }
        try {
            bw.write(logString, 0, logString.length());
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
