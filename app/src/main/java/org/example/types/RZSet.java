package org.example.types;

import org.example.store.ByteKey;

import java.util.*;

public final class RZSet {
    public record ScoredMember(ByteKey member, double score) {}

    private final Map<ByteKey, Double> dict = new HashMap<>();
    private final SkipList skipList = new SkipList();

    public synchronized int size() {
        return dict.size();
    }

    public synchronized boolean isEmpty() {
        return dict.isEmpty();
    }

    /**
     * Adds member with score.
     * @return 1 if added as new element, 0 if score updated, -1 if no change.
     */
    public synchronized int add(double score, ByteKey member) {
        Double curScore = dict.get(member);
        if (curScore != null) {
            if (curScore == score) {
                return -1; // unchanged
            }
            skipList.delete(curScore, member);
            skipList.insert(score, member);
            dict.put(member, score);
            return 0; // updated
        } else {
            dict.put(member, score);
            skipList.insert(score, member);
            return 1; // added
        }
    }

    public synchronized boolean remove(ByteKey member) {
        Double score = dict.remove(member);
        if (score != null) {
            skipList.delete(score, member);
            return true;
        }
        return false;
    }

    public synchronized Double getScore(ByteKey member) {
        return dict.get(member);
    }

    /**
     * Returns 0-based rank, or null if member not found.
     */
    public synchronized Long getRank(ByteKey member, boolean reverse) {
        Double score = dict.get(member);
        if (score == null) {
            return null;
        }
        int rank1 = skipList.getRank(score, member);
        if (rank1 <= 0) {
            return null;
        }
        if (reverse) {
            return (long) (skipList.getLength() - rank1);
        } else {
            return (long) (rank1 - 1);
        }
    }

    public synchronized List<ScoredMember> rangeByRank(long start, long stop, boolean reverse) {
        int len = skipList.getLength();
        if (len == 0) return Collections.emptyList();

        if (start < 0) start = len + start;
        if (stop < 0) stop = len + stop;
        if (start < 0) start = 0;
        if (start >= len || start > stop) return Collections.emptyList();
        if (stop >= len) stop = len - 1;

        List<ScoredMember> result = new ArrayList<>((int) (stop - start + 1));

        if (!reverse) {
            SkipList.Node node = skipList.getNodeByRank(start + 1);
            long count = stop - start + 1;
            while (node != null && count > 0) {
                result.add(new ScoredMember(node.member, node.score));
                node = node.levels[0].forward;
                count--;
            }
        } else {
            // Reverse rank: rank 0 is highest score (at end of skiplist)
            long revStartRank = len - start;
            SkipList.Node node = skipList.getNodeByRank(revStartRank);
            long count = stop - start + 1;
            while (node != null && count > 0) {
                result.add(new ScoredMember(node.member, node.score));
                node = node.backward;
                count--;
            }
        }

        return result;
    }

    public synchronized List<ScoredMember> rangeByScore(double min, double max, int offset, int limit) {
        if (limit == 0 || min > max) {
            return Collections.emptyList();
        }

        List<ScoredMember> result = new ArrayList<>();
        SkipList.Node node = skipList.getHeader().levels[0].forward;

        // Skip until >= min
        while (node != null && node.score < min) {
            node = node.levels[0].forward;
        }

        int skipped = 0;
        while (node != null && node.score <= max) {
            if (skipped < offset) {
                skipped++;
            } else {
                result.add(new ScoredMember(node.member, node.score));
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
            }
            node = node.levels[0].forward;
        }

        return result;
    }

    public synchronized int count(double min, double max) {
        int cnt = 0;
        SkipList.Node node = skipList.getHeader().levels[0].forward;
        while (node != null && node.score < min) {
            node = node.levels[0].forward;
        }
        while (node != null && node.score <= max) {
            cnt++;
            node = node.levels[0].forward;
        }
        return cnt;
    }

    public synchronized Map<ByteKey, Double> getEntries() {
        return Collections.unmodifiableMap(dict);
    }

    public long estimateSize() {
        return 64 + (long) dict.size() * (64 + 16);
    }
}
