### What is it?
It's a mod for Fabric 1.20.1 made by BluSpring and slightly edited by Velamniro.

### What does it do?
It lets you export your CPM model as a .cpmproject file. It's not perfect though, so you still shouldn't delete your .cpmproject.

### How to use it?
1. [Build the mod](https://docs.fabricmc.net/develop/getting-started/building-a-mod) using JDK 17.
2. Install this mod alongside with [CPM](https://modrinth.com/plugin/custom-player-models/version/1.20v0.6.24a-fabric) itself.
3. Load any world.
4. Make sure the model you want to export is loaded in-game.
     1. If it's not, run `/cpmclient set_model <file_name>` (the model has to be located at `[minecraft's directory]/player_models`). If your model is stored in your skin, make sure your skin loads properly and etc.
5. Run `/exportmodel <output_file_name>`. Now the output file is located at `[minecraft's directory]/player_models/<output_file_name>.cpmproject`.