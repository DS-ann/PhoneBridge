package com.dsann.batterymonitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class BatteryPercentOverride {
    private BatteryPercentOverride() {}

    static Result set(int percent) {
        if (percent < 0 || percent > 100) {
            return new Result(false, "Enter a value from 0 to 100.");
        }
        return runRoot("dumpsys battery set level " + percent,
                "Battery percentage set to " + percent + "%.");
    }

    static Result reset() {
        return runRoot("dumpsys battery reset",
                "Battery percentage reset to the real value.");
    }

    private static Result runRoot(String command, String successMessage) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            StringBuilder output = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }
            StringBuilder error = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (error.length() > 0) error.append('\n');
                    error.append(line);
                }
            }
            int code = p.waitFor();
            if (code == 0) return new Result(true, successMessage);
            String detail = error.length() > 0 ? error.toString() : output.toString();
            return new Result(false, "Root command failed" +
                    (detail.isEmpty() ? "." : ": " + detail));
        } catch (Exception e) {
            return new Result(false, "Root access failed: " + e.getMessage());
        }
    }

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
