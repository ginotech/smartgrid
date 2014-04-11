package njit.smartgrid;

public class PowerRequest {

    static final int POWER_HIGH = 60;
    static final int POWER_LOW = 40;
    static final int POWER_BOTH = POWER_HIGH + POWER_LOW;

    private int powerRequested;
    private int powerGranted;

    public PowerRequest(int powerRequested) {
        this.powerRequested = powerRequested;
        this.powerGranted = 0;
    }

    public int getPowerRequested() { return powerRequested; }

    public int getPowerGranted() { return powerGranted; }

    public void setPowerGranted(int powerGranted) { this.powerGranted = powerGranted; }

}
