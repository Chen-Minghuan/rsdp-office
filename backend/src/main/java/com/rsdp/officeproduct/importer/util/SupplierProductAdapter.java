package com.rsdp.officeproduct.importer.util;

import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules for the ITEM NO. supplier catalogue. Its size column was confirmed to use millimetres. */
public final class SupplierProductAdapter {
    private static final Pattern THREE = Pattern.compile("^\\s*(\\d+)\\s*[xX×*]\\s*(\\d+)\\s*[xX×*]\\s*(\\d+)\\s*(?:mm|毫米)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_TWO = Pattern.compile("^\\s*(\\d+)\\s*[xX×*]\\s*(\\d+)\\s*[xX×*]\\s*.+$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TWO_WIDTHS = Pattern.compile("^\\s*(\\d+)\\s*[/／]\\s*(\\d+)\\s*[xX×*]\\s*(\\d+)\\s*[xX×*]\\s*(\\d+)\\s*(?:mm|毫米)?\\s*$", Pattern.CASE_INSENSITIVE);

    private SupplierProductAdapter() { }

    /** Only two explicit width choices with a shared depth and height can be split safely. */
    public static List<String> widthAlternatives(String raw) {
        if (raw == null) return List.of();
        Matcher match = TWO_WIDTHS.matcher(raw);
        if (!match.matches() || match.group(1).equals(match.group(2))) return List.of();
        return List.of(match.group(1) + "x" + match.group(3) + "x" + match.group(4),
            match.group(2) + "x" + match.group(3) + "x" + match.group(4));
    }

    public static void fillDimensions(String raw, Map<String, String> fields) {
        if (raw == null) return;
        Matcher exact = THREE.matcher(raw);
        if (exact.matches()) {
            fields.put("widthMm", exact.group(1));
            fields.put("depthMm", exact.group(2));
            fields.put("heightMm", exact.group(3));
            return;
        }
        // An alternative height does not make the first two dimensions uncertain.
        Matcher firstTwo = FIRST_TWO.matcher(raw);
        if (firstTwo.matches()) {
            fields.put("widthMm", firstTwo.group(1));
            fields.put("depthMm", firstTwo.group(2));
        }
    }

    public static int[] exactDimensions(String raw) {
        if (raw == null) return null;
        Matcher match = THREE.matcher(raw);
        if (!match.matches()) return null;
        try {
            return new int[]{Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
