package org.example.types;

import org.example.store.ByteKey;

import java.util.Random;

public final class SkipList {
    public static final int MAX_LEVEL = 32;
    public static final double P = 0.25;

    public static final class Level {
        public Node forward;
        public int span;
    }

    public static final class Node {
        public final ByteKey member;
        public double score;
        public Node backward;
        public final Level[] levels;

        public Node(int level, double score, ByteKey member) {
            this.levels = new Level[level];
            for (int i = 0; i < level; i++) {
                this.levels[i] = new Level();
            }
            this.score = score;
            this.member = member;
        }
    }

    private final Node header;
    private Node tail;
    private int length;
    private int level;
    private final Random random = new Random();

    public SkipList() {
        this.level = 1;
        this.length = 0;
        this.header = new Node(MAX_LEVEL, 0, null);
        for (int i = 0; i < MAX_LEVEL; i++) {
            this.header.levels[i].forward = null;
            this.header.levels[i].span = 0;
        }
        this.header.backward = null;
        this.tail = null;
    }

    public int getLength() {
        return length;
    }

    public Node getHeader() {
        return header;
    }

    public Node getTail() {
        return tail;
    }

    private int randomLevel() {
        int lvl = 1;
        while ((random.nextInt() & 0xFFFF) < (0xFFFF * P) && lvl < MAX_LEVEL) {
            lvl++;
        }
        return lvl;
    }

    public Node insert(double score, ByteKey member) {
        Node[] update = new Node[MAX_LEVEL];
        int[] rank = new int[MAX_LEVEL];
        Node x = header;

        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.levels[i].forward != null &&
                    (x.levels[i].forward.score < score ||
                            (x.levels[i].forward.score == score && x.levels[i].forward.member.compareTo(member) < 0))) {
                rank[i] += x.levels[i].span;
                x = x.levels[i].forward;
            }
            update[i] = x;
        }

        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                rank[i] = 0;
                update[i] = header;
                update[i].levels[i].span = length;
            }
            level = lvl;
        }

        x = new Node(lvl, score, member);
        for (int i = 0; i < lvl; i++) {
            x.levels[i].forward = update[i].levels[i].forward;
            update[i].levels[i].forward = x;

            x.levels[i].span = update[i].levels[i].span - (rank[0] - rank[i]);
            update[i].levels[i].span = (rank[0] - rank[i]) + 1;
        }

        for (int i = lvl; i < level; i++) {
            update[i].levels[i].span++;
        }

        x.backward = (update[0] == header) ? null : update[0];
        if (x.levels[0].forward != null) {
            x.levels[0].forward.backward = x;
        } else {
            tail = x;
        }
        length++;
        return x;
    }

    private void deleteNode(Node x, Node[] update) {
        for (int i = 0; i < level; i++) {
            if (update[i].levels[i].forward == x) {
                update[i].levels[i].span += x.levels[i].span - 1;
                update[i].levels[i].forward = x.levels[i].forward;
            } else {
                update[i].levels[i].span -= 1;
            }
        }
        if (x.levels[0].forward != null) {
            x.levels[0].forward.backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && header.levels[level - 1].forward == null) {
            level--;
        }
        length--;
    }

    public boolean delete(double score, ByteKey member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;

        for (int i = level - 1; i >= 0; i--) {
            while (x.levels[i].forward != null &&
                    (x.levels[i].forward.score < score ||
                            (x.levels[i].forward.score == score && x.levels[i].forward.member.compareTo(member) < 0))) {
                x = x.levels[i].forward;
            }
            update[i] = x;
        }

        x = x.levels[0].forward;
        if (x != null && score == x.score && x.member.equals(member)) {
            deleteNode(x, update);
            return true;
        }
        return false;
    }

    /**
     * Finds 1-based rank of member with score. Returns 0 if not found.
     */
    public int getRank(double score, ByteKey member) {
        int rank = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.levels[i].forward != null &&
                    (x.levels[i].forward.score < score ||
                            (x.levels[i].forward.score == score && x.levels[i].forward.member.compareTo(member) <= 0))) {
                rank += x.levels[i].span;
                x = x.levels[i].forward;
            }
            if (x != null && x.member != null && x.member.equals(member)) {
                return rank;
            }
        }
        return 0;
    }

    /**
     * Finds node by 1-based rank.
     */
    public Node getNodeByRank(long rank) {
        if (rank <= 0 || rank > length) {
            return null;
        }
        Node x = header;
        long traversed = 0;
        for (int i = level - 1; i >= 0; i--) {
            while (x.levels[i].forward != null && (traversed + x.levels[i].span) <= rank) {
                traversed += x.levels[i].span;
                x = x.levels[i].forward;
            }
            if (traversed == rank) {
                return x;
            }
        }
        return null;
    }
}
