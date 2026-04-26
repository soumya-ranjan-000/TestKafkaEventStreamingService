package gammazon.automation.utils;

import java.util.Random;

public class RandomGenerator {

    public static Integer generateNumber(int length) {
        if (length <= 0) return 0;

        StringBuilder number = new StringBuilder();
        Random random = new Random();

        // First digit should not be 0
        number.append(random.nextInt(9) + 1);

        // Remaining digits can be 0-9
        for (int i = 1; i < length; i++) {
            number.append(random.nextInt(10));
        }
        return Integer.parseInt(number.toString());
    }
}
