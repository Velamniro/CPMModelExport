### What is it?
It's a mod for Fabric 1.20.1 made by BluSpring and slightly edited by Velamniro.

### What does it do?
It lets you export your CPM model as a .cpmproject file. It's not perfect though, so you still shouldn't delete your .cpmproject.

### How to use it?
1. [Build this mod](https://docs.fabricmc.net/develop/getting-started/building-a-mod) using JDK 17 (higher also should work).
2. Install this mod alongside with [CPM](https://modrinth.com/plugin/custom-player-models/version/1.20v0.6.26a-fabric) itself.
3. Load any world.
4. Make sure the model you want to export is loaded in-game.
     1. If it's not, run `/cpmclient set_model <file_name>` (the model has to be located at `[minecraft's directory]/player_models`). If your model is [stored-in-skin](https://github.com/tom5454/CustomPlayerModels/blob/master/Localization/wiki/start/Exporting.md#stored-in-skin) one, make sure your skin loads properly, etc.
5. Run `/exportmodel <output_file_name>`. Now the output file is located at `[minecraft's directory]/player_models/<output_file_name>.cpmproject`.

### What has changed compared to the original project?
1. Support for [stored-in-skin](https://github.com/tom5454/CustomPlayerModels/blob/master/Localization/wiki/start/Exporting.md#stored-in-skin) models has been added. Now the mod gets the currently loaded model instead of reading the one you provide.
2. Support for exporting [remotely hosted](https://github.com/tom5454/CustomPlayerModels/wiki/Exporting#data-overflow) models (for example, stored in GitHub Gists) has been added.
3. The command has been renamed to `/exportmodel` (it used to be `/loadmodel`). It now also asks for the name of the .cpmproject file.
4. The code no longer uses deprecated features (such as [`ModelPartDefinition`](https://github.com/tom5454/CustomPlayerModels/blob/master/CustomPlayerModels/src/shared/java/com/tom/cpm/shared/parts/ModelPartDefinition.java)).
5. The code is much easier to read now.