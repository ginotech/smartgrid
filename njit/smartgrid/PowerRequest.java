package njit.smartgrid;

public class PowerRequest {

    static final int POWER_HIGH = 60;
    static final int POWER_LOW = 40;
    static final int POWER_BOTH = POWER_HIGH + POWER_LOW;

    private int packetsRequested;
    private int packetsRemaining;
    private int powerRequested;
    private int powerGranted;

    public PowerRequest(int packetsRequested, int powerRequested) {
        this.packetsRequested = packetsRequested;
        this.packetsRemaining = 0;
        this.powerRequested = powerRequested;
        this.powerGranted = 0;
    }

    public int getPowerRequested() { return powerRequested; }

    public int getPowerGranted() { return powerGranted; }

    public int getPacketsRequested() { return packetsRequested; }

    public int getPacketsRemaining() {
        return packetsRemaining;
    }

    public void setPowerGranted(int powerGranted) { this.powerGranted = powerGranted; }

    public void setPacketsRequested(int packetsRequested) { this.packetsRequested = packetsRequested; }

    public void setPacketsRemaining(int packetsRemaining) {
        this.packetsRemaining = packetsRemaining;
    }

    public void decrementPacketsRemaining() { this.packetsRemaining--; }

}
