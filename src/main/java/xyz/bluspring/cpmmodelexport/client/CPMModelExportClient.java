package xyz.bluspring.cpmmodelexport.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO;
import com.tom.cpm.client.CustomPlayerModelsClient;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.effects.*;
import com.tom.cpm.shared.io.ChecksumOutputStream;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.parts.*;
import com.tom.cpm.shared.skin.TextureProvider;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.Util;
import xyz.bluspring.cpmmodelexport.mixin.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@SuppressWarnings("UnreachableCode")
public class CPMModelExportClient implements ClientModInitializer {
    /**
     * Runs the mod initializer on the client environment.
     */
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("loadmodel")
                .then(
                    ClientCommandManager.argument("output_file_name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                var player = CustomPlayerModelsClient.mc.getCurrentClientPlayer();
                                var definition = player.getModelDefinition();

                                ModelPartDefinition part;
                                if ((((ModelDefinitionAccessor) definition).getParts().get(1)) instanceof ModelPartDefinition) {
                                    part = (ModelPartDefinition) ((ModelDefinitionAccessor) definition).getParts().get(1);
                                } else { // otherwise it's ModelPartDefinitionLink, which has to be resolved
                                    part = (ModelPartDefinition) ((ModelDefinitionAccessor) definition).getParts().get(1).resolve();
                                }


                                var effects = new LinkedList<IRenderEffect>();
                                TextureProvider skinData = null;

                                for (IModelPart other : ((ModelPartDefinitionAccessor) part).getOtherParts()) {
                                    if (other instanceof ModelPartRenderEffect renderEffect) {
                                        effects.add(((ModelPartRenderEffectAccessor) renderEffect).getEffect());
                                    }

                                    if (other instanceof ModelPartSkin skin) {
                                        var skinOut = new ByteArrayOutputStream();
                                        var skinIo = new IOHelper(skinOut);
                                        skin.write(skinIo);

                                        var readIo = new IOHelper(new ByteArrayInputStream(skinOut.toByteArray()));

                                        skinData = new TextureProvider(readIo, null);
                                    }
                                }


                                var idxToIdMapping = new Int2ObjectAVLTreeMap<String>();
                                var parentChildMapping = new Int2ObjectAVLTreeMap<List<Integer>>();
                                var mainElements = new LinkedList<JsonObject>();

                                for (RenderedCube rc : ((ModelPartDefinitionAccessor) part).getRc()) {
                                    var cube = rc.getCube();
                                    var uuid = UUID.randomUUID();
                                    idxToIdMapping.put(cube.id, uuid.toString());

                                    if (!parentChildMapping.containsKey(cube.parentId)) {
                                        parentChildMapping.put(cube.parentId, new ArrayList<>());
                                    }
                                    parentChildMapping.get(cube.parentId).add(mainElements.size());

                                    mainElements.add(createElementFromRenderedCube(rc, uuid));
                                }


                                var elements = new JsonArray();
                                var modelElements = new JsonArray();
                                var newElements = new Int2ObjectAVLTreeMap<JsonObject>();

                                var head = createEmptyElement("head", modelElements);
                                var torso = createEmptyElement("body", modelElements);
                                var leftArm = createEmptyElement("left_arm", modelElements);
                                var rightArm = createEmptyElement("right_arm", modelElements);
                                var leftLeg = createEmptyElement("left_leg", modelElements);
                                var rightLeg = createEmptyElement("right_leg", modelElements);

                                for (int i = 0; i < modelElements.size(); i++) {
                                    var element = (JsonObject) modelElements.get(i);
                                    var childrenIdx = parentChildMapping.get(i);

                                    if (childrenIdx != null) {
                                        var children = new JsonArray();
                                        for (int idx : childrenIdx) {
                                            var child = mainElements.get(idx);
                                            children.add(child);
                                        }

                                        element.add("children", children);
                                    }

                                    newElements.put(i, element);
                                }

                                for (JsonObject element : mainElements) {
                                    var internalId = element.get("internal_id").getAsInt();

                                    if (internalId > 5) {
                                        var childrenIdx = parentChildMapping.get(internalId);

                                        if (childrenIdx != null) {
                                            var children = new JsonArray();
                                            for (int idx : childrenIdx) {
                                                var child = mainElements.get(idx);
                                                children.add(child);
                                            }

                                            element.add("children", children);
                                        }
                                    }

                                    for (IRenderEffect effect : effects) {
                                        if (effect instanceof EffectGlow glow && ((EffectGlowAccessor) glow).getId() == internalId) {
                                            element.addProperty("glow", true);
                                        }

                                        if (effect instanceof EffectScale scale && ((EffectScaleAccessor) scale).getId() == internalId) {
                                            var scaleAccessor = ((EffectScaleAccessor) scale);

                                            element.add("scale", vec(scaleAccessor.getScale().x, scaleAccessor.getScale().y, scaleAccessor.getScale().z));
                                            element.addProperty("mcScale", scaleAccessor.getMcScale());
                                        }

                                        if (effect instanceof EffectPerFaceUV && ((EffectPerFaceUVAccessor) effect).getId() == internalId) {
                                            element.add("faceUV", createPerFaceUVJson(((EffectPerFaceUVAccessor) effect).getUv()));
                                        }
                                    }
                                }

                                newElements.forEach((i, obj) -> {
                                    elements.add(obj);
                                });


                                createProjectFile(StringArgumentType.getString(ctx, "output_file_name"), effects, skinData, elements);

                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            return 1;
                        })
                ));
        }));
    }

    /**
     * Creates empty Enc Anim JSON
     *
     * @return empty Enc Anim as JSON
     */
    private JsonObject createEmptyEncAnim() {
        var json = new JsonObject();

        var freeLayers = new JsonArray();
        freeLayers.add("right_pants_leg");
        freeLayers.add("left_sleeve");
        freeLayers.add("left_pants_leg");
        freeLayers.add("jacket");
        freeLayers.add("hat");
        freeLayers.add("right_sleeve");

        var defaultValues = new JsonObject();
        defaultValues.addProperty("left_sleeve", false);
        defaultValues.addProperty("right_sleeve", false);
        defaultValues.addProperty("left_pants_leg", false);
        defaultValues.addProperty("right_pants_leg", false);
        defaultValues.addProperty("jacket", false);
        defaultValues.addProperty("hat", false);

        json.add("freeLayers", freeLayers);
        json.add("defaultValues", defaultValues);

        return json;
    }

    /**
     * Creates empty element and adds it to the provided parent
     *
     * @param id     used in {@code id} field
     * @param parent the empty element is added to the parent
     * @return       the empty element
     */
    private JsonObject createEmptyElement(String id, JsonArray parent) {
        var json = new JsonObject();
        var array = new JsonArray();

        json.addProperty("disableVanillaAnim", false);
        json.add("children", array);
        json.add("pos", zeroVec());
        json.add("rotation", zeroVec());
        json.addProperty("nameColor", 0);
        json.addProperty("show", false);
        json.addProperty("name", "");
        json.addProperty("id", id);
        json.addProperty("locked", false);
        json.addProperty("showInEditor", true);
        json.addProperty("dup", false);

        parent.add(json);

        return json;
    }

    /**
     * Creates a 2D vector in JSON format
     *
     * @param x x coordinate
     * @param y y coordinate
     * @return vector as JsonObject
     */
    private JsonObject vec(float x, float y) {
        var json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);

        return json;
    }

    /**
     * Creates a 3D vector in JSON format
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return vector as JsonObject
     */
    private JsonObject vec(float x, float y, float z) {
        var json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);

        return json;
    }

    /**
     * Returns zero vector
     *
     * @return zero vector as JsonObject
     */
    private JsonObject zeroVec() {
        return vec(0f, 0f, 0f);
    }

    private JsonObject transform(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        var json = new JsonObject();

        json.add("position", vec(px, py, pz));
        json.add("rotation", vec(rx, ry, rz));
        json.add("scale", vec(sx, sy, sz));

        return json;
    }

    private JsonObject emptyTransform() {
        var json = new JsonObject();

        json.add("position", zeroVec());
        json.add("rotation", zeroVec());
        json.add("scale", zeroVec());

        return json;
    }

    private void storeModel(String name, String desc, Image icon, byte[] data) throws IOException {
        File models = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
        models.mkdirs();
        File out = new File(models, name.replaceAll("[^a-zA-Z0-9\\.\\-]", "") + ".cpmmodel");

        String var10003;
        for(Random r = new Random(); out.exists(); out = new File(models, var10003 + "_" + Integer.toHexString(r.nextInt()) + ".cpmmodel")) {
            var10003 = name.replaceAll("[^a-zA-Z0-9\\.\\-]", "");
        }

        FileOutputStream fout = new FileOutputStream(out);

        try {
            fout.write(ModelDefinitionLoader.HEADER);
            ChecksumOutputStream cos = new ChecksumOutputStream(fout);
            IOHelper h = new IOHelper(cos);
            h.writeUTF(name);
            h.writeUTF(desc != null ? desc : "");
            h.writeVarInt(data.length);
            h.write(data);
            h.writeVarInt(0);
            if (icon != null) {
                h.writeImage(icon);
            } else {
                h.writeVarInt(0);
            }

            cos.close();
        } catch (Throwable var12) {
            try {
                fout.close();
            } catch (Throwable var11) {
                var12.addSuppressed(var11);
            }

            throw var12;
        }

        fout.close();
    }

    /**
     * Creates a JSON representation of {@code PerFaceUV}
     *
     * @param perFaceUV the object to serialize
     * @return JSON representation of {@code PerFaceUV}
     */
    private JsonObject createPerFaceUVJson(PerFaceUV perFaceUV) {
        var faceUvJson = new JsonObject();

        perFaceUV.faces.forEach((direction, face) -> {
            var faceUv = new JsonObject();

            faceUv.addProperty("ex", face.ex);
            faceUv.addProperty("ey", face.ey);
            faceUv.addProperty("sx", face.sx);
            faceUv.addProperty("sy", face.sy);
            faceUv.addProperty("rot", Integer.toString(face.rotation.ordinal() * 90));
            faceUv.addProperty("autoUV", face.autoUV);

            faceUvJson.add(direction.name().toLowerCase(Locale.ROOT), faceUv);
        });

        return faceUvJson;
    }

    /**
     * Create an element from RenderedCube
     *
     * @param rc provides data required to build the element representation
     * @param uuid a UUID, used as the element name and as part of {@code storeId}
     * @return JSON representation of the model element
     */
    private JsonObject createElementFromRenderedCube(RenderedCube rc, UUID uuid) {
        var cube = rc.getCube();
        var id = uuid.toString();
        var json = new JsonObject();

        json.addProperty("internal_id", cube.id);
        json.addProperty("internal_parent_id", cube.parentId);
        json.addProperty("name", id);
        json.addProperty("mirror", false);
        json.add("offset", vec(rc.offset.x, rc.offset.y, rc.offset.z));
        json.addProperty("color", Integer.toHexString(rc.color));
        json.addProperty("hidden", cube.hidden);
        json.addProperty("texture", cube.texSize != 0);
        json.addProperty("nameColor", rc.color);
        var rot = rc.rotation.asVec3f(true);
        json.add("rotation", vec(rot.x, rot.y, rot.z));
        json.addProperty("show", rc.display);
        json.add("scale", vec(rc.renderScale.x, rc.renderScale.y, rc.renderScale.z));
        json.addProperty("storeId", uuid.getMostSignificantBits());
        json.addProperty("textureSize", cube.texSize);
        json.addProperty("mcScale", cube.mcScale);
        json.add("size", vec(cube.size.x, cube.size.y, cube.size.z));
        json.add("pos", vec(cube.pos.x, cube.pos.y, cube.pos.z));
        json.addProperty("u", cube.u);
        json.addProperty("v", cube.v);
        json.addProperty("singleTex", rc.singleTex);
        json.addProperty("extrude", rc.extrude);
        json.addProperty("recolor", rc.recolor);
        json.addProperty("locked", false);
        json.addProperty("glow", rc.glow);

        if (rc.faceUVs != null) {
            json.add("faceUV", createPerFaceUVJson(rc.faceUVs));
        }

        return json;
    }

    /**
     * Generates a config for a model.
     *
     * @param effects a list of {@code IRenderEffect} to be parsed and written into the config
     * @param skinData skin data from {@code ModelPartSkin} contained in {@code ModelDefinition}
     * @param elements a JSON representation of elements (parts) of a model
     * @return the model config as {@code JsonObject}
     */
    private JsonObject generateConfig(LinkedList<IRenderEffect> effects, TextureProvider skinData, JsonArray elements) {
        var configJson = new JsonObject();

        configJson.addProperty("removeBedOffset", false);
        configJson.addProperty("scaling", 0.0);
        configJson.add("textures", Util.make(() -> {
            var json = new JsonObject();
            json.add("skin", Util.make(() -> {
                var j = new JsonObject();
                j.addProperty("customGridSize", false);
                j.add("anim", new JsonArray());
                return j;
            }));

            return json;
        }));

        var invisGlow = false;
        var hideSkull = false;
        var removeArmorOffset = true;
        var removeBedOffset = false;
        var firstPersonHand = Util.make(() -> {
            var json = new JsonObject();

            json.add("left", emptyTransform());
            json.add("right", emptyTransform());

            return json;
        });
        for (IRenderEffect effect : effects) {
            if (effect instanceof EffectInvisGlow) {
                invisGlow = true;
            }

            if (effect instanceof EffectHideSkull) {
                hideSkull = ((EffectHideSkullAccessor) effect).isHide();
            }

            if (effect instanceof EffectRemoveArmorOffset) {
                removeArmorOffset = ((EffectRemoveArmorOffsetAccessor) effect).isRemove();
            }

            if (effect instanceof EffectRemoveBedOffset) {
                removeBedOffset = true;
            }

            if (effect instanceof EffectFirstPersonHandPos) {
                var left = ((EffectFirstPersonHandPosAccessor) effect).getLeftHand();
                var right = ((EffectFirstPersonHandPosAccessor) effect).getRightHand();

                firstPersonHand.add("left", transform(left.getRPos().x, left.getRPos().y, left.getRPos().z, left.getRotationDeg().x, left.getRotationDeg().y, left.getRotationDeg().z, left.getRScale().x, left.getRScale().y, left.getRScale().z));
                firstPersonHand.add("right", transform(right.getRPos().x, right.getRPos().y, right.getRPos().z, right.getRotationDeg().x, right.getRotationDeg().y, right.getRotationDeg().z, right.getRScale().x, right.getRScale().y, right.getRScale().z));
            }

            if (effect instanceof EffectScaling) {

            }
        }

        configJson.addProperty("enableInvisGlow", invisGlow);
        configJson.addProperty("hideHeadIfSkull", hideSkull);
        configJson.addProperty("removeArmorOffset", removeArmorOffset);
        configJson.addProperty("removeBedOffset", removeBedOffset);
        configJson.add("firstPersonHand", firstPersonHand);
        configJson.add("skinSize", vec(skinData.size.x, skinData.size.y));
        configJson.addProperty("version", 1);

        configJson.add("elements", elements);

        return configJson;
    }

    /**
     * Creates a .cpmproject file for a model at {@code minecraft/player_models/<name>.cpmproject} path
     *
     * @param name       the name of the output file
     * @param effects    a list of effects applied to the model
     * @param skinData   model skin data
     * @param elements   model elements in JSON format
     * @throws IOException if an I/O error occurs while writing the file
     */
    private void createProjectFile(String name, LinkedList<IRenderEffect> effects, TextureProvider skinData, JsonArray elements) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();

        File models = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
        models.mkdirs();
        File out = new File(models, name.replaceAll("[^a-zA-Z0-9\\.\\-]", "") + ".cpmproject");

        String var10003;
        for(Random r = new Random(); out.exists(); out = new File(models, var10003 + "_" + Integer.toHexString(r.nextInt()) + ".cpmproject")) {
            var10003 = name.replaceAll("[^a-zA-Z0-9\\.\\-]", "");
        }
        FileOutputStream fout2 = new FileOutputStream(out);
        var zout = new ZipOutputStream(fout2);

        zout.putNextEntry(new ZipEntry("config.json"));
        zout.write(gson.toJson(generateConfig(effects, skinData, elements)).getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();

        zout.putNextEntry(new ZipEntry("anim_enc.json"));
        zout.write(gson.toJson(createEmptyEncAnim()).getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();

        zout.putNextEntry(new ZipEntry("skin.png"));
        var baos = new ByteArrayOutputStream();
        ImageIO.write(skinData.getImage(), baos);
        zout.write(baos.toByteArray());
        zout.closeEntry();

        zout.close();
    }
}
