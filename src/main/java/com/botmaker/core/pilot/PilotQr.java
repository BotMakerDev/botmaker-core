// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

import com.botmaker.shared.Diag;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pairing address as a QR code's modules, so a client draws it without a QR library of its own: a window as
 * squares, a terminal as block characters.
 */
public final class PilotQr {

    /** The quiet zone around the code, in modules; the standard asks for four. */
    static final int MARGIN = 4;

    private PilotQr() {
    }

    /** One string per row, {@code '1'} a dark module, the quiet zone included; empty when it cannot be encoded. */
    public static List<String> rows(String text) {
        BitMatrix matrix;
        try {
            // Asked for no size, the writer gives one cell per module.
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, MARGIN, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            Diag.warn("Pilot", "Could not make the pairing QR code: " + e.getMessage());
            return List.of();
        }
        List<String> rows = new ArrayList<>(matrix.getHeight());
        for (int y = 0; y < matrix.getHeight(); y++) {
            StringBuilder row = new StringBuilder(matrix.getWidth());
            for (int x = 0; x < matrix.getWidth(); x++) row.append(matrix.get(x, y) ? '1' : '0');
            rows.add(row.toString());
        }
        return rows;
    }
}
