package com.nikohalfkino.rubydung.entity.player.chess;

import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.interaction.HitResult;
import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.piece.ChessPiece;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;

import java.util.Collections;
import java.util.List;

/**
 * the chess piece picked up with the interact key and the squares it may go to
 *
 * a piece is an entity now (see ChessPiece): it is picked by aiming at it (CubeManager.getTargetedPiece, worked out every frame by the renderer). with one picked, the interact key on a lit square sends it there (the floor under the square is what the crosshair is on), or on a piece that can be taken takes it. the piece then glides, see ChessPieceTile.moveTo
 */
public class ChessSelection {

    private Level level;
    private ChessPiece piece;
    private ChessPieceTile tile;
    private int x, y, z;
    private List<ChessPieceTile.Move> moves = Collections.emptyList();

    public boolean isActive() {
        return this.piece != null;
    }

    /** the side of the picked piece (the lit squares take its colour) */
    public boolean isWhite() {
        return this.piece != null && this.piece.pieceWhite();
    }

    /** the square the picked piece stands on */
    public int getX() { return this.x; }

    public int getY() { return this.y; }

    public int getZ() { return this.z; }

    public List<ChessPieceTile.Move> getMoves() {
        return this.moves;
    }

    public void clear() {
        this.piece = null;
        this.tile = null;
        this.moves = Collections.emptyList();
    }

    /** keeps the moves up to date (the board changes under a picked piece) and drops the selection when the piece is gone or the level is another */
    public void tick(Level current) {
        if (current != this.level) {
            clear();
            this.level = current;
        }
        if (this.piece == null) {
            return;
        }
        if (!current.getCubeManager().all().contains(this.piece)) {
            clear();
            return;
        }
        refresh();
    }

    private void refresh() {
        this.tile = ChessPieceTile.of(this.piece);
        if (this.tile == null) {
            clear();
            return;
        }
        this.x = this.piece.pieceCellX();
        this.y = this.piece.pieceCellY();
        this.z = this.piece.pieceCellZ();
        this.moves = this.piece.gliding ? Collections.<ChessPieceTile.Move>emptyList()
                : this.tile.getMoves(this.level, this.x, this.y, this.z);
    }

    private ChessPieceTile.Move moveTo(int cellX, int cellZ, boolean capture) {
        for (ChessPieceTile.Move move : this.moves) {
            if (move.x == cellX && move.z == cellZ && move.capture == capture) {
                return move;
            }
        }
        return null;
    }

    /** a lit square (or a piece that can be taken) is what the crosshair is on */
    public boolean isAimingAtMove(HitResult hit) {
        if (this.piece == null || this.level == null) {
            return false;
        }
        ChessPiece target = this.level.getCubeManager().getTargetedPiece();
        if (target != null) {
            return target != this.piece && moveTo(target.pieceCellX(), target.pieceCellZ(), true) != null;
        }
        return hit != null && hit.y == this.y - 1 && moveTo(hit.x, hit.z, false) != null;
    }

    /** the interact key: picks the piece under the crosshair, or sends the picked one to the square aimed at. true if it did something */
    public boolean interact(Level current, Player player, HitResult hit) {
        if (current != this.level) {
            clear();
            this.level = current;
        }

        CubeManager pieces = current.getCubeManager();
        ChessPiece target = pieces.getTargetedPiece();

        if (this.piece != null && isAimingAtMove(hit)) {
            int toX;
            int toZ;
            if (target != null) {
                toX = target.pieceCellX();
                toZ = target.pieceCellZ();
            } else {
                toX = hit.x;
                toZ = hit.z;
            }
            this.tile.moveTo(current, this.x, this.y, this.z, toX, toZ);
            clear();
            HandHud.swingCurrent(HandHud.Action.INTERACT);
            return true;
        }

        if (target != null) {
            if (target == this.piece) {
                clear();   // aiming at the picked one again puts it down
            } else {
                this.piece = target;
                refresh();
            }
            HandHud.swingCurrent(HandHud.Action.INTERACT);
            return true;
        }
        return false;
    }
}
