package smartgrid;

import java.net.InetAddress;

public class PowerRequest {

    private InetAddress myAddr;
    private int powerRequested;

    public PowerRequest(InetAddress myAddr, int powerRequested) {
        this.myAddr = myAddr;
        this.powerRequested = powerRequested;
    }

    public InetAddress getAddress() {
        return myAddr;
    }

    public int getPowerRequested() {
        return powerRequested;
    }

    public void setPowerRequested(int newPower) {
        this.powerRequested = newPower;
    }
}
