package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.Console;

import java.util.Arrays;
import java.util.List;

public class SingCommand extends LocalCommand {

    @Override
    public String name() {
        return "sing";
    }

    @Override
    public String description() {
        return "Singing syntax for chat (type 'sing help')";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Any chat line containing a note or a pitch marker switches on singing automatically.",
                "",
                "Notes:  phoneme<duration,pitch>",
                "  phoneme   what is sung, e.g. bah, doh, la",
                "  duration  length in milliseconds (1-30000)",
                "  pitch     25 = C4, +1 per half-tone (30 = F4, 20 = G3),",
                "            or a note name like G4, G#4, Bb3",
                "  Example:  bah<235,30>",
                "",
                "Phrases: put several notes in one bracket group.",
                "  Example:  [bah<235,30>bah<235,25>bah<470,32>]",
                "  Spaces and extra brackets between notes are ignored.",
                "",
                "Pitch markers (alternative): [N] before a word sets its pitch.",
                "  [+7]  7 half-tones above the base pitch",
                "  [-3]  3 half-tones below",
                "  [G4]  note name (C4 = 0, C5 = +12); also [G#4] and [Bb3]",
                "  Example:  [0]do [2]re [4]mi [5]fa [7]sol",
                "  Range is -24 to +24 half-tones. Without markers the",
                "  singNote setting is used as the pitch.",
                "",
                "Notes take priority: if a line has <duration,pitch> notes,",
                "pitch markers in that line are ignored.");
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        console.println("Type 'sing help' for the singing syntax.");
    }
}