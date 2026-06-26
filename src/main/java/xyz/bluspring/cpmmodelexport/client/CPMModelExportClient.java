package xyz.bluspring.cpmmodelexport.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.tom.cpl.util.DynamicTexture;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO;
import com.tom.cpm.client.CustomPlayerModelsClient;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.animation.CustomPose;
import com.tom.cpm.shared.animation.IPose;
import com.tom.cpm.shared.animation.VanillaPose;
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

@SuppressWarnings({"UnreachableCode", "deprecation"})
public class CPMModelExportClient implements ClientModInitializer {
    /**
     * Runs the mod initializer on the client environment.
     */
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("exportmodel")
                .then(
                    ClientCommandManager.argument("output_file_name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                var player = CustomPlayerModelsClient.mc.getCurrentClientPlayer();
                                var definition = player.getModelDefinition();

                                IModelPart modelPart = ((ModelDefinitionAccessor) definition).getParts().get(1);
                                ModelPartDefinition part = modelPart instanceof ModelPartDefinition def
                                        ? def
                                        : (ModelPartDefinition) modelPart.resolve();

                                var animationData = new HashMap<Integer, ResolvedDataAccessor>();
                                var renderEffects = new LinkedList<IRenderEffect>();
                                TextureProvider skinData = new TextureProvider();

                                for (IModelPart modelOtherPart : ((ModelPartDefinitionAccessor) part).getOtherParts()) {
                                    collectIModelPartData(modelOtherPart, renderEffects, skinData, animationData);
                                }

                                var parentChildMapping = new Int2ObjectAVLTreeMap<List<Integer>>();
                                var unsortedModelElements = new LinkedList<JsonObject>();

                                for (RenderedCube rc : ((ModelPartDefinitionAccessor) part).getRc()) {
                                    var cube = rc.getCube();

                                    if (!parentChildMapping.containsKey(cube.parentId)) {
                                        parentChildMapping.put(cube.parentId, new ArrayList<>());
                                    }
                                    parentChildMapping.get(cube.parentId).add(unsortedModelElements.size());

                                    unsortedModelElements.add(createModelElementFromRenderedCube(rc));
                                }

                                var sortedModelElements = new JsonArray();
                                var basicModelElements = new JsonArray();
                                var modelElementsMap = new Int2ObjectAVLTreeMap<JsonObject>();

                                basicModelElements.add(createDefaultElement("head"));
                                basicModelElements.add(createDefaultElement("body"));
                                basicModelElements.add(createDefaultElement("left_arm"));
                                basicModelElements.add(createDefaultElement("right_arm"));
                                basicModelElements.add(createDefaultElement("left_leg"));
                                basicModelElements.add(createDefaultElement("right_leg"));

                                for (int i = 0; i < basicModelElements.size(); i++) {
                                    var element = (JsonObject) basicModelElements.get(i);
                                    attachChildren(unsortedModelElements, element, parentChildMapping.get(i));
                                    modelElementsMap.put(i, element);
                                }

                                for (JsonObject element : unsortedModelElements) {
                                    var internalId = element.get("internal_id").getAsInt();

                                    if (internalId > 5) {
                                        attachChildren(unsortedModelElements, element, parentChildMapping.get(internalId));
                                    }

                                    for (IRenderEffect effect : renderEffects) {
                                        attachRenderEffect(element, effect);
                                    }
                                }

                                modelElementsMap.forEach((i, obj) -> {
                                    sortedModelElements.add(obj);
                                });

                                LinkedList<JsonObject> animationList = parseAnimationData(animationData);

                                var outputFileName = StringArgumentType.getString(ctx, "output_file_name");
                                var configJson = generateConfig(renderEffects, skinData, sortedModelElements);
                                var encAnimJson = createEmptyEncAnim();
                                createProjectFile(outputFileName, configJson, encAnimJson, skinData, animationList);

                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            return 1;
                        })
                ));
        }));
    }

    /**
     * Creates an empty Enc Anim.
     *
     * @return empty Enc Anim as a {@code JsonObject} instance
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
     * Creates a default (i.e. with fixed data) element and adds it to the provided parent.
     *
     * @param id     used in {@code id} field
     * @return       the empty element as a {@code JsonObject} instance
     */
    private JsonObject createDefaultElement(String id) {
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

        return json;
    }

    /**
     * Creates a 2D vector in JSON format.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @return vector as a {@code JsonObject} instance
     */
    private JsonObject vec(float x, float y) {
        var json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);

        return json;
    }

    /**
     * Creates a 3D vector in JSON format.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param z z coordinate
     * @return vector as a {@code JsonObject} instance
     */
    private JsonObject vec(float x, float y, float z) {
        var json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);

        return json;
    }

    /**
     * Creates a zero vector in JSON format.
     *
     * @return zero vector as a {@code JsonObject} instance
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
     * Creates a JSON representation of a {@code PerFaceUV} instance.
     *
     * @param perFaceUV the {@code PerFaceUV} instance to be serialized
     * @return          the {@code PerFaceUV} instance as a {@code JsonObject} instance
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
     * Create a model element from a {@code RenderedCube} instance in JSON format.
     *
     * @param rc a {@code RenderedCube} instance used to build the element
     * @return   the model element as a {@code JsonObject} instance
     */
    private JsonObject createModelElementFromRenderedCube(RenderedCube rc) {
        var cube = rc.getCube();
        var uuid = UUID.randomUUID();
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
     * Generates a config for a model in JSON format.
     *
     * @param effects  a list of {@code IRenderEffect} instances to be parsed and written into the config
     * @param skinData model's skin data as a {@code TextureProvider} instance
     * @param elements elements (parts) of a model as a {@code JsonArray} instance
     * @return         the model config as a {@code JsonObject} instance
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
     * using provided data.
     *
     * @param outputFileName  the name of the output file
     * @param configJson      the config.json file as a {@code JsonObject} instance
     * @param encAnimJson     the enc_anim.json file as a {@code JsonObject} instance
     * @param skinData        model skin data a {@code TextureProvider} instance
     * @throws IOException    if an I/O error occurs while writing the project file
     */
    private void createProjectFile(String outputFileName, JsonObject configJson, JsonObject encAnimJson, TextureProvider skinData, LinkedList<JsonObject> animationList) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();

        File models = new File(MinecraftClientAccess.get().getGameDir(), "player_models");
        models.mkdirs();
        File out = new File(models, outputFileName.replaceAll("[^a-zA-Z0-9\\.\\-]", "") + ".cpmproject");

        String var10003;
        for(Random r = new Random(); out.exists(); out = new File(models, var10003 + "_" + Integer.toHexString(r.nextInt()) + ".cpmproject")) {
            var10003 = outputFileName.replaceAll("[^a-zA-Z0-9\\.\\-]", "");
        }

        try (var zout = new ZipOutputStream(new FileOutputStream(out))) {
            zout.putNextEntry(new ZipEntry("config.json"));
            zout.write(gson.toJson(configJson).getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();

            zout.putNextEntry(new ZipEntry("anim_enc.json"));
            zout.write(gson.toJson(encAnimJson).getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();

            zout.putNextEntry(new ZipEntry("skin.png"));
            var baos = new ByteArrayOutputStream();
            ImageIO.write(skinData.getImage(), baos);
            zout.write(baos.toByteArray());
            zout.closeEntry();

            zout.putNextEntry(new ZipEntry("animations/"));
            addAnimationsToZout(zout, animationList, gson);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Attaches children from list to parent element.
     *
     * @param elements           where to search for children
     * @param parentElement      where to add children
     * @param childrenIDs        list of children IDs
     */
    private void attachChildren(LinkedList<JsonObject> elements, JsonObject parentElement, List<Integer> childrenIDs) {
        if (childrenIDs != null) {
            var children = new JsonArray();
            for (int id : childrenIDs) {
                var child = elements.get(id);
                children.add(child);
            }

            parentElement.add("children", children);
        }
    }

    /**
     * Attaches an {@code IRenderEffect} instance to the element if their ID match each other.
     *
     * @param element           where to attach render effect
     * @param renderEffect      an {@code IRenderEffect} instance to be attached
     */
    private void attachRenderEffect(JsonObject element, IRenderEffect renderEffect) {
        var internalElementID = element.get("internal_id").getAsInt();

        if (renderEffect instanceof EffectGlow glow && ((EffectGlowAccessor) glow).getId() == internalElementID) {
            element.addProperty("glow", true);
        }

        if (renderEffect instanceof EffectScale scale && ((EffectScaleAccessor) scale).getId() == internalElementID) {
            var scaleAccessor = ((EffectScaleAccessor) scale);

            element.add("scale", vec(scaleAccessor.getScale().x, scaleAccessor.getScale().y, scaleAccessor.getScale().z));
            element.addProperty("mcScale", scaleAccessor.getMcScale());
        }

        if (renderEffect instanceof EffectPerFaceUV && ((EffectPerFaceUVAccessor) renderEffect).getId() == internalElementID) {
            element.add("faceUV", createPerFaceUVJson(((EffectPerFaceUVAccessor) renderEffect).getUv()));
        }

    }

    /**
     * Collects data from an {@code IModelPart} instance to the corresponding list or variable.
     *
     * @param modelPart    an {@code IModelPart} instance to write in
     * @param effectsList  where to store {@code ModelPartRenderEffect} instances
     * @param skinData     where to store {@code ModelPartSkin} instances
     */
    private void collectIModelPartData(IModelPart modelPart, LinkedList<IRenderEffect> effectsList, TextureProvider skinData, HashMap<Integer, ResolvedDataAccessor> animationData) {
        try {
            if (modelPart instanceof ModelPartRenderEffect renderEffect) {
                effectsList.add(((ModelPartRenderEffectAccessor) renderEffect).getEffect());
            }

            if (modelPart instanceof ModelPartSkin skin) {
                var skinOut = new ByteArrayOutputStream();
                var skinIo = new IOHelper(skinOut);
                skin.write(skinIo);

                var readIo = new IOHelper(new ByteArrayInputStream(skinOut.toByteArray()));

                skinData.size = readIo.read2s();
                IOHelper.ImageBlock block = readIo.readImage();
                block.doReadImage();
                skinData.texture = new DynamicTexture(block.getImage());
            }

            if (modelPart instanceof ModelPartAnimation animation) {
                ((ModelPartAnimationAccessor) animation).getParsedData().forEach((i, o) -> {
                    animationData.put(i, (ResolvedDataAccessor) o);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private LinkedList<JsonObject> parseAnimationData(HashMap<Integer, ResolvedDataAccessor> animationData) {
        var resultList = new LinkedList<JsonObject>();

        animationData.forEach((i, rdAccessor) -> {
            var animationJson = new JsonObject();
            IPose pose = rdAccessor.getPose();

            animationJson.addProperty("hidden", rdAccessor.isButtonHidden());
//            animationJson.addProperty("maxValue", rdAccessor.getMaxValue());
            animationJson.addProperty("mustFinish", rdAccessor.isFinish());
            animationJson.addProperty("priority", rdAccessor.getPriority());
            animationJson.addProperty("command", rdAccessor.isCommand());
            animationJson.addProperty("additive", rdAccessor.isAdd());
            animationJson.addProperty("duration", rdAccessor.getDuration());
            animationJson.addProperty("interpolator", rdAccessor.getIt().name().toLowerCase());
            animationJson.addProperty("loop", rdAccessor.isLoop());
            animationJson.addProperty("name", rdAccessor.getName());
            animationJson.addProperty("layerControlled", rdAccessor.isLayerCtrl());
            animationJson.addProperty("isProperty", rdAccessor.isIsProperty());
            animationJson.addProperty("interpolateVal", rdAccessor.isInterpolateVal());
            animationJson.addProperty("order", rdAccessor.getOrder());

            if (pose instanceof VanillaPose) {
                animationJson.addProperty("pose", ((VanillaPose) pose).name().toLowerCase());
            } else {
                animationJson.addProperty("pose", ((CustomPose) pose).getName().toLowerCase());
            }

            resultList.add(animationJson);
        });

        return resultList;
    }

    private void addAnimationsToZout(ZipOutputStream zout, LinkedList<JsonObject> animationList, Gson gson) throws IOException {
        for (JsonObject animation : animationList) {
            var uuid = UUID.randomUUID();
            zout.putNextEntry(new ZipEntry("animations/v_" + animation.get("pose").getAsString() + "_" +
                    animation.get("name").toString() + "_" + uuid.toString() + ".json"));
            zout.write(gson.toJson(animation).getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
    }
}
