package com.nolapeles.twitter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Utils {
    public static List<String> getStringLines(File f) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        Scanner scanner = new Scanner(f);
        while (scanner.hasNextLine()) {
            result.add(scanner.nextLine().trim());
        }
        scanner.close();
        return result;
    }
}
