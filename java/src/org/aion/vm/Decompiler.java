package org.aion.vm;

public class Decompiler {

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static void main(String args[]) {
        if (args.length < 1) {
            System.out.println("Usage: java -jar decompiler.jar [code]");
            return;
        }

        String hex = args[0].startsWith("0x") ? args[0].substring(2) : args[0];
        byte[] bytes = hexStringToByteArray(hex);

        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];

            Instruction inst = Instruction.of(b & 0xff);
            System.out.print(inst);

            if (b >= 0x60 && b <= 0x7F) {
                int n = b - 0x60 + 1;
                System.out.print("\t" + hex.substring((i + 1) * 2, (i + 1) * 2 + n * 2));
                i += n;
            }
            System.out.println();
        }

    }
}
