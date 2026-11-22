/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glyph;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that turns all Glyph lights on or off. */
public class GlyphTileService extends TileService {
    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        GlyphSettings.setEnabled(this, !GlyphSettings.isEnabled(this));
        GlyphEngine.get(this).refresh();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(GlyphSettings.isEnabled(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
