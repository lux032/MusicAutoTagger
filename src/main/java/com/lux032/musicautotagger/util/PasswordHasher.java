package com.lux032.musicautotagger.util;

import org.mindrot.jbcrypt.BCrypt;

public class PasswordHasher {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java com.lux032.musicautotagger.util.PasswordHasher <password>");
            System.exit(1);
        }

        String password = args[0];
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        System.out.println(hash);
    }
}

