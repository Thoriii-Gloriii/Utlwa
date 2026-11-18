package com.buddyapps.utlwa;

/** Structured mock content — clearly a demo, not connected to a streaming service. */
final class Data {
    static final class Song {
        final String title, artist;
        final int seed;
        final int seconds;
        Song(String title, String artist, int seed, int seconds) {
            this.title = title; this.artist = artist; this.seed = seed; this.seconds = seconds;
        }
    }

    static final class Collection {
        final String title, subtitle;
        final int seed;
        Collection(String title, String subtitle, int seed) {
            this.title = title; this.subtitle = subtitle; this.seed = seed;
        }
    }

    static final Song[] TRENDING = {
        new Song("Lose Control", "Teddy Swims", 11, 213),
        new Song("Beautiful Things", "Benson Boone", 12, 180),
        new Song("Too Sweet", "Hozier", 13, 254),
        new Song("Better Days", "Burna Boy", 14, 232),
        new Song("A Bar Song", "Shaboozey", 15, 167),
        new Song("Espresso", "Sabrina Carpenter", 16, 175)
    };

    static final Collection[] RECENT = {
        new Collection("Chill Vibes", "Playlist", 21),
        new Collection("Top Hits", "Playlist", 22),
        new Collection("Afrobeats Hits", "Album", 23),
        new Collection("Late Night Drive", "Playlist", 24)
    };

    static final Collection[] MADE_FOR_YOU = {
        new Collection("Your Daily Mix", "A mix of your favourites", 31),
        new Collection("Discover Weekly", "Fresh finds for you", 32),
        new Collection("Golden Hour", "Warm, mellow sounds", 33)
    };

    static final Collection[] LIBRARY = {
        new Collection("My Favourites", "152 songs", 41),
        new Collection("Chill Vibes", "87 songs", 42),
        new Collection("Workout Energy", "72 songs", 43),
        new Collection("Afrobeats Hits", "110 songs", 44),
        new Collection("Road Trip", "64 songs", 45),
        new Collection("Focus Flow", "38 songs", 46)
    };

    static final String[] GENRES = {"Hip-Hop", "Pop", "Afrobeats", "Rock", "Electronic", "Chill"};
    static final int[] GENRE_SEEDS = {51, 52, 53, 54, 55, 56};

    private Data() {}
}
