package com.dsann.batterymonitor;

public final class DozeController {
    private DozeController() {}

    public static Result forceDoze() {
        return runRoot("dumpsys deviceidle force-idle",
                "Doze forced on.");
    }

    public static Result disableForceDoze() {
        return runRoot("dumpsys deviceidle unforce",
                "Forced Doze disabled. Normal idle behavior restored.");
    }

    private static Result runRoot(String command, String success) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            int code = p.waitFor();
            if (code == 0) return new Result(true, success);
            String message = err.trim().isEmpty() ? out.trim() : err.trim();
            return new Result(false, message.isEmpty() ? "Command failed." : message);
        } catch (Exception e) {
            return new Result(false, e.getMessage() == null ? "Root command failed." : e.getMessage());
        }
    }

    private static String read(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
