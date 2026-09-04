package io.github.fourilla.endervault.common;

import java.util.Comparator;
import java.util.Objects;

/**
 * Compares names by treating consecutive ASCII digits as numeric chunks.
 */
public final class NaturalNameComparator implements Comparator<String> {

    public static final NaturalNameComparator INSTANCE = new NaturalNameComparator();

    private NaturalNameComparator() {
    }

    @Override
    public int compare(String left, String right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");

        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftCharacter = left.charAt(leftIndex);
            char rightCharacter = right.charAt(rightIndex);
            if (isAsciiDigit(leftCharacter) && isAsciiDigit(rightCharacter)) {
                int leftEnd = skipDigits(left, leftIndex);
                int rightEnd = skipDigits(right, rightIndex);
                int result = compareNumberChunks(left, leftIndex, leftEnd, right, rightIndex, rightEnd);
                if (result != 0) {
                    return result;
                }
                leftIndex = leftEnd;
                rightIndex = rightEnd;
                continue;
            }

            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            int result = compareCodePointsIgnoreCase(leftCodePoint, rightCodePoint);
            if (result != 0) {
                return result;
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }

        int lengthResult = Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
        if (lengthResult != 0) {
            return lengthResult;
        }
        return left.compareTo(right);
    }

    private int compareNumberChunks(
            String left,
            int leftStart,
            int leftEnd,
            String right,
            int rightStart,
            int rightEnd
    ) {
        int leftSignificantStart = skipLeadingZeroes(left, leftStart, leftEnd);
        int rightSignificantStart = skipLeadingZeroes(right, rightStart, rightEnd);
        int leftSignificantLength = leftEnd - leftSignificantStart;
        int rightSignificantLength = rightEnd - rightSignificantStart;

        int lengthResult = Integer.compare(leftSignificantLength, rightSignificantLength);
        if (lengthResult != 0) {
            return lengthResult;
        }
        for (int offset = 0; offset < leftSignificantLength; offset++) {
            int digitResult = Character.compare(
                    left.charAt(leftSignificantStart + offset),
                    right.charAt(rightSignificantStart + offset)
            );
            if (digitResult != 0) {
                return digitResult;
            }
        }
        return Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
    }

    private int skipLeadingZeroes(String value, int start, int end) {
        int index = start;
        while (index < end - 1 && value.charAt(index) == '0') {
            index++;
        }
        return index;
    }

    private int skipDigits(String value, int start) {
        int index = start;
        while (index < value.length() && isAsciiDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private int compareCodePointsIgnoreCase(int left, int right) {
        if (left == right) {
            return 0;
        }
        int upperLeft = Character.toUpperCase(left);
        int upperRight = Character.toUpperCase(right);
        if (upperLeft == upperRight) {
            return 0;
        }
        return Integer.compare(Character.toLowerCase(upperLeft), Character.toLowerCase(upperRight));
    }
}
