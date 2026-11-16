package com.nikohalfkino.rubydung.entity.piece;

import com.nikohalfkino.rubydung.entity.EntityTypes;

/**
 * a chess piece: a MovablePiece with a side and a "has moved" flag. its type is one of the six chess types of EntityTypes, picked from the code, and never changes
 *
 * the code (also what the save file and the network know it by): bits 0-2 the type (ChessPieceTile.Type ordinal), bit 3 white, bit 4 has moved
 */
public final class ChessPiece extends MovablePiece {

    public static final int WHITE = 8;
    public static final int MOVED = 16;

    private int code;

    public ChessPiece(double x, double y, double z, int code) {
        super(EntityTypes.forPieceCode(code), x, y, z);
        this.code = code;
    }

    public static int pieceCode(int typeOrdinal, boolean white, boolean moved) {
        return typeOrdinal | (white ? WHITE : 0) | (moved ? MOVED : 0);
    }

    @Override
    public int pieceCode() {
        return this.code;
    }

    @Override
    public void applyPieceCode(int code) {
        if (code >= 0) {
            this.code = code;
        }
    }

    @Override
    protected void onGlideStart() {
        this.code |= MOVED;
    }

    public int pieceTypeOrdinal() {
        return this.code & 7;
    }

    public boolean pieceWhite() {
        return (this.code & WHITE) != 0;
    }

    public boolean pieceMoved() {
        return (this.code & MOVED) != 0;
    }
}
