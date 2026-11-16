<p align="center">
  <img src="src/main/resources/assets/rubydung/textures/gui/logo.png" alt="Strata logo">
</p>

Strata is a fork of LabyStudio's decomp/recreation of Minecraft's rd-132211 ([original repo](https://github.com/thecodeofnotch/rd-132211)).

It's a creative sandbox focused game, with lots of unique tiles and world types!

## Download

Windows and Linux builds are available on the [Releases](../../releases) page. Download the zip for your platform, extract it, and run `Strata` or `Strata.exe` on Windows. <br> (No Java install needed!)

### Building from source

You need JDK 11 or newer to build and run the game. Building a native app image also needs JDK 17 or newer, because `jpackage` isn't included in JDK 11.

```bash
./gradlew run          # run the game
./gradlew packageApp   # build a native executable into build/package (JDK 17+)
```

## Music

The current background music is by [windows96](https://windows96.bandcamp.com/album/plume-valley), from their album *Plume Valley*. It probably won't stay for long, as it's mostly there to test game audio, for now...

## Text-to-speech credits

Chat text-to-speech uses [Open JTalk](http://open-jtalk.sourceforge.net/) with the following voices:

- HTS Voice "Mei" and HTS Voice "Takumi" © Nagoya Institute of Technology, released by the
  [MMDAgent Project Team](http://www.mmdagent.jp/), licensed under
  [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).

Full license texts are in `jtalk/common/voice/` inside the game folder.

## Contributors

- **ZLD1**