### What is it?
It's a mod for Fabric 1.20.1

### What it does?
It lets you convert your .cpmmodel file (or model, stored in skin) into .cpmproject file. It's not perfect, so you still shouldn't delete your .cpmproject.

### How to use it?
1. [Build the mod](https://docs.fabricmc.net/develop/getting-started/building-a-mod) using JDK 17.
2. Install it in your game alongside with [CPM](https://modrinth.com/plugin/custom-player-models/version/1.20v0.6.24a-fabric) itself.
3. Load any world.
4. Make sure the model you want to convert is loaded in-game. 
     1. If it's not, run `/cpmclient set_model <file_name>`. The model itself has to be located at `[minecraft's directory]/player_models`. If your model is stored in your skin, go and check if your skin loads properly and so on.
5. Run `/loadmodel <output_file_name>`. Now the output file is located at `[minecraft's directory]/player_models`.