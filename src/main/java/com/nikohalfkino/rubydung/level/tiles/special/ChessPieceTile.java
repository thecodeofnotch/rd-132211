package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.entity.piece.ChessPiece;
import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.TileRegistry;
import com.nikohalfkino.rubydung.level.tiles.common.BillboardTile;
import com.nikohalfkino.rubydung.net.Multiplayer;

import java.util.ArrayList;
import java.util.List;

/**
 * a chess piece. the tile is what the tile selector, the hotbar and the hand show, and what knows where a piece may go: it is never stored in a level.
 * setting it (placing it) stands a piece entity on the square instead (a ChessPiece, see CubeManager.spawnPiece, Level.setTile),
 * which is a camera facing sprite that glides to the square it is sent to. the methods taking a cell (getMoves, moveTo, at) work on the piece
 * standing on that square
 *
 * the moves are worked out on the horizontal plane of the cell the piece stands in. a square is open when its cell is empty and has a solid block
 * under it (so the board is simply whatever floor the pieces stand on, of any size), an opposing piece on it can be captured, anything else is in
 * the way. a pawn that has not moved yet may open with two steps
 *
 * sizes: the textures are 16 pixels to the block. pawn, rook and knight are 16x32 (1 x 2 blocks), queen and king 32x48 (2 x 3), bishop 16x48
 * (1 x 3). the hitboxes: pawn, rook, knight and bishop 1 x 2, queen and king 1 x 4
 *
 * white moves toward -z (north), black toward +z. not done: castling, en passant, promotion, check (a king can be taken like any piece)
 */
public class ChessPieceTile extends BillboardTile {

    public enum Type {
        //   id        sprite w, h        hitbox w, h      (blocks)
        KING("king",       2, 3,              1, 4),
        QUEEN("queen",     2, 3,              1, 4),
        ROOK("rook",       1, 2,              1, 2),
        BISHOP("bishop",   1, 3,              1, 2),
        KNIGHT("knight",   1, 2,              1, 2),
        PAWN("pawn",       1, 2,              1, 2);

        public final String id;
        /** size of the sprite drawn, the texture size over 16 */
        public final float spriteWidth, spriteHeight;
        /** size of the box that is picked */
        public final float hitWidth, hitHeight;

        Type(String id, float spriteWidth, float spriteHeight, float hitWidth, float hitHeight) {
            this.id = id;
            this.spriteWidth = spriteWidth;
            this.spriteHeight = spriteHeight;
            this.hitWidth = hitWidth;
            this.hitHeight = hitHeight;
        }

        /** the texture of the piece, a resource path */
        public String texturePath(boolean white) {
            return "/assets/rubydung/textures/block/chess/" + this.id + "_" + (white ? "white" : "black") + ".png";
        }
    }

    /** a square a piece can go to */
    public static final class Move {
        public final int x;
        public final int z;
        /** an opposing piece stands there and is taken */
        public final boolean capture;

        Move(int x, int z, boolean capture) {
            this.x = x;
            this.z = z;
            this.capture = capture;
        }
    }

    /** how far a bishop, rook or queen reaches along a line */
    public static final int MAX_SLIDE = 8;

    /** data byte of a piece that has moved (only an old level has it, the entity keeps it now) */
    public static final int DATA_MOVED = 1;

    private static final int FREE = 0;
    private static final int ENEMY = 1;
    private static final int BLOCKED = 2;

    private static final int[][] ORTHOGONAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] KNIGHT_JUMPS = {
            {1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}
    };

    private final Type type;
    private final boolean white;

    /** texture is chess/<type>_<colour>, e.g. chess/knight_black */
    public ChessPieceTile(Type type, boolean white) {
        super("chess_" + type.id + "_" + (white ? "white" : "black"),
                "chess/" + type.id + "_" + (white ? "white" : "black"), type.spriteHeight);
        this.type = type;
        this.white = white;
    }

    public Type getType() {
        return this.type;
    }

    public boolean isWhite() {
        return this.white;
    }

    @Override
    public boolean isInteractable() {
        return true;
    }

    /** a piece has a front: it is never flipped like a bush */
    @Override
    protected boolean isMirrored(int x, int y, int z) {
        return false;
    }

    @Override
    public int getPlacementData(int face) {
        return 0;
    }

    /** the kind of a piece entity, null if it is not one */
    public static ChessPieceTile of(ChessPiece piece) {
        if (piece == null || !piece.isPiece()) {
            return null;
        }
        Tile tile = TileRegistry.byName("chess_" + Type.values()[piece.pieceTypeOrdinal()].id + "_" + (piece.pieceWhite() ? "white" : "black"));
        return tile instanceof ChessPieceTile ? (ChessPieceTile) tile : null;
    }

    /** the kind of the piece standing on the square, null if there is none */
    public static ChessPieceTile at(Level level, int x, int y, int z) {
        return of(level.getCubeManager().pieceAt(x, y, z));
    }

    /**
     * sends the piece standing at (x, y, z) to (targetX, targetZ) of the same level, taking the piece there. it glides, it is not there at once. nothing is checked, ask getMoves first. when joined to a server the server is asked to do it (and checks it), what happens comes back with the pieces
     */
    public void moveTo(Level level, int x, int y, int z, int targetX, int targetZ) {
        CubeManager pieces = level.getCubeManager();
        ChessPiece piece = pieces.pieceAt(x, y, z);
        if (piece == null) {
            return;
        }
        if (pieces.isReplica()) {
            Multiplayer.chessMove(piece, targetX, targetZ);
        } else {
            pieces.movePiece(piece, targetX, targetZ);
        }
    }

    /** every square this piece standing at (x, y, z) may go to */
    public List<Move> getMoves(Level level, int x, int y, int z) {
        List<Move> moves = new ArrayList<>();
        switch (this.type) {
            case KING:
                for (int[] d : ORTHOGONAL) {
                    step(level, moves, x, y, z, d[0], d[1]);
                }
                for (int[] d : DIAGONAL) {
                    step(level, moves, x, y, z, d[0], d[1]);
                }
                break;
            case QUEEN:
                for (int[] d : ORTHOGONAL) {
                    slide(level, moves, x, y, z, d[0], d[1]);
                }
                for (int[] d : DIAGONAL) {
                    slide(level, moves, x, y, z, d[0], d[1]);
                }
                break;
            case ROOK:
                for (int[] d : ORTHOGONAL) {
                    slide(level, moves, x, y, z, d[0], d[1]);
                }
                break;
            case BISHOP:
                for (int[] d : DIAGONAL) {
                    slide(level, moves, x, y, z, d[0], d[1]);
                }
                break;
            case KNIGHT:
                for (int[] d : KNIGHT_JUMPS) {
                    step(level, moves, x, y, z, d[0], d[1]);
                }
                break;
            case PAWN:
                pawn(level, moves, x, y, z);
                break;
        }
        return moves;
    }

    private void pawn(Level level, List<Move> moves, int x, int y, int z) {
        int forward = this.white ? -1 : 1;

        if (classify(level, x, y, z + forward) == FREE) {
            moves.add(new Move(x, z + forward, false));

            ChessPiece self = level.getCubeManager().pieceAt(x, y, z);
            boolean unmoved = self == null || !self.pieceMoved();
            if (unmoved && classify(level, x, y, z + 2 * forward) == FREE) {
                moves.add(new Move(x, z + 2 * forward, false));
            }
        }

        // a pawn only takes diagonally forward
        for (int side = -1; side <= 1; side += 2) {
            if (classify(level, x + side, y, z + forward) == ENEMY) {
                moves.add(new Move(x + side, z + forward, true));
            }
        }
    }

    private void step(Level level, List<Move> moves, int x, int y, int z, int dx, int dz) {
        int state = classify(level, x + dx, y, z + dz);
        if (state != BLOCKED) {
            moves.add(new Move(x + dx, z + dz, state == ENEMY));
        }
    }

    private void slide(Level level, List<Move> moves, int x, int y, int z, int dx, int dz) {
        for (int i = 1; i <= MAX_SLIDE; i++) {
            int tx = x + dx * i;
            int tz = z + dz * i;
            int state = classify(level, tx, y, tz);
            if (state == BLOCKED) {
                return;
            }
            moves.add(new Move(tx, tz, state == ENEMY));
            if (state == ENEMY) {
                return;
            }
        }
    }

    // FREE: may stand there. ENEMY: an opposing piece, may be taken. BLOCKED: out of the level, no floor, a block or a friend
    private int classify(Level level, int x, int y, int z) {
        if (x < 0 || z < 0 || x >= level.width || z >= level.height) {
            return BLOCKED;
        }
        if (level.getSubTiles().has(x, y, z)) {
            return BLOCKED;
        }

        // a piece is an entity, the cell itself is empty while one stands on it
        ChessPieceTile other = at(level, x, y, z);
        if (other != null) {
            return other.white != this.white ? ENEMY : BLOCKED;
        }

        int id = level.getTile(x, y, z);
        if (id == Tile.AIR.id) {
            Tile floor = Tile.byId(level.getTile(x, y - 1, z));
            boolean solidFloor = floor != null && floor.isSolid() && !floor.isBillboard();
            return solidFloor ? FREE : BLOCKED;
        }
        return BLOCKED;
    }
}
