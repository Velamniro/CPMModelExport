package xyz.bluspring.cpmmodelexport.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.ImageIO;
import com.tom.cpm.client.CustomPlayerModelsClient;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.animation.*;
import com.tom.cpm.shared.animation.interpolator.*;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.model.RootModelElement;
import com.tom.cpm.shared.model.TextureSheetType;
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
            dispatcher.register(ClientCommandManager.literal("exportmodel")
                .then(
                    ClientCommandManager.argument("output_file_name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            try {
                                var player = CustomPlayerModelsClient.mc.getCurrentClientPlayer();
                                var definition = player.getModelDefinition();
                                if (definition.getResolveState() != ModelDefinition.ModelLoadingState.LOADED) {
                                    System.out.println("Wait for your model to load!");
                                    return 1;
                                }

                                var parentChildMapping = new Int2ObjectAVLTreeMap<List<Integer>>();
                                var rootElementsMapping = new HashMap<Integer, Integer>();
                                var internalIdStoreIdMapping = new HashMap<Integer, Long>();
                                var unsortedElements = new LinkedList<JsonObject>();

                                // parsing RenderedCubes and filling up these three variables
                                for (RenderedCube rc : ((ModelDefinitionAccessor) definition).getCubes()) {
                                    var cube = rc.getCube();
                                    if (cube != null) {
                                        if (!parentChildMapping.containsKey(cube.parentId)) {
                                            parentChildMapping.put(cube.parentId, new ArrayList<>());
                                        }
                                        parentChildMapping.get(cube.parentId).add(unsortedElements.size());

                                        JsonObject element = createElementFromRenderedCube(rc);
                                        internalIdStoreIdMapping.put(rc.getId(), element.get("storeID").getAsLong());
                                        unsortedElements.add(element);
                                    } else if (((RootModelElement) rc).getPart() instanceof PlayerModelParts) {
                                        rootElementsMapping.put(rc.getId(), unsortedElements.size());
                                        JsonObject element = createElementFromRenderedCube(rc);
                                        internalIdStoreIdMapping.put(rc.getId(), element.get("storeID").getAsLong());
                                        unsortedElements.add(element);
                                    }
                                }

                                // Adding children to elements according to parentChildMapping
                                for (JsonObject element : unsortedElements) {
                                    var internalId = element.get("internal_id").getAsInt();
                                    var childrenIDs = parentChildMapping.get(internalId);

                                    if (childrenIDs != null) {
                                        var children = new JsonArray();
                                        for (int id : childrenIDs) {
                                            children.add(unsortedElements.get(id));
                                        }

                                        element.add("children", children);
                                    }
                                }

                                var sortedElements = new JsonArray();

                                for (int i = 0; i < rootElementsMapping.size(); i++) {
                                    var rootElement = unsortedElements.get(rootElementsMapping.get(i));
                                    sortedElements.add(rootElement);
                                }

                                var animationData = new HashMap<Integer, AnimationTrigger>();
                                var i = 0;

                                for (AnimationTrigger trigger : definition.getAnimations().getAnimations()) {
                                    animationData.put(i, trigger);
                                    i++;
                                }

                                LinkedList<JsonObject> animationList = parseAnimationData(animationData, internalIdStoreIdMapping);

                                var outputFileName = StringArgumentType.getString(ctx, "output_file_name");
                                var configJson = createConfigJson(definition, sortedElements);
                                var encAnimJson = createEmptyEncAnim();
                                createProjectFile(outputFileName, configJson, encAnimJson, definition.getTexture(TextureSheetType.SKIN, true), animationList);
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

    /**
     * Create a model element from a {@code RenderedCube} instance in JSON format.
     *
     * @param rc a {@code RenderedCube} instance used to build the element
     * @return   the model element as a {@code JsonObject} instance
     */
    private JsonObject createElementFromRenderedCube(RenderedCube rc) {
        var json = new JsonObject();
        Vec3f rot;
        var uuid = UUID.randomUUID();
        var id = uuid.toString();

        // universal properties
        json.addProperty("internal_id", rc.getId());
        json.addProperty("show", rc.display);
        json.addProperty("nameColor", rc.color);
        json.addProperty("hidden", rc.isHidden());
        json.addProperty("locked", false);
        json.addProperty("storeID", uuid.getMostSignificantBits());

        if (!(rc instanceof RootModelElement)) {
            var cube = rc.getCube();
            rot = rc.rotation.asVec3f(true);

            json.addProperty("internal_parent_id", cube.parentId);
            json.addProperty("name", id);
            json.addProperty("mirror", false);
            json.add("offset", vec(rc.offset.x, rc.offset.y, rc.offset.z));
            json.addProperty("color", Integer.toHexString(rc.color));
            json.addProperty("texture", cube.texSize != 0);
            json.add("scale", vec(rc.renderScale.x, rc.renderScale.y, rc.renderScale.z));
            json.addProperty("textureSize", cube.texSize);
            json.addProperty("mcScale", cube.mcScale);
            json.add("size", vec(cube.size.x, cube.size.y, cube.size.z));
            json.add("pos", vec(cube.pos.x, cube.pos.y, cube.pos.z));
            json.addProperty("u", cube.u);
            json.addProperty("v", cube.v);
            json.addProperty("singleTex", rc.singleTex);
            json.addProperty("extrude", rc.extrude);
            json.addProperty("recolor", rc.recolor);
            json.addProperty("glow", rc.glow);
        } else {
            rot = ((RootModelElement) rc).rotN.asVec3f(true);

            json.addProperty("disableVanillaAnim", ((RootModelElement) rc).disableVanilla);
            json.add("pos", vec(((RootModelElement) rc).posN.x, ((RootModelElement) rc).posN.y, ((RootModelElement) rc).posN.z));
            json.addProperty("name", "");
            json.addProperty("id", ((RootModelElement) rc).getPart().getName());
            json.addProperty("showInEditor", true);
            json.addProperty("dup", false);
        }
        json.add("rotation", vec(rot.x, rot.y, rot.z));

        if (rc.faceUVs != null) {
            var faceUVsJson = new JsonObject();

            rc.faceUVs.faces.forEach((direction, face) -> {
                var faceJson = new JsonObject();

                faceJson.addProperty("ex", face.ex);
                faceJson.addProperty("ey", face.ey);
                faceJson.addProperty("sx", face.sx);
                faceJson.addProperty("sy", face.sy);
                faceJson.addProperty("rot", Integer.toString(face.rotation.ordinal() * 90));
                faceJson.addProperty("autoUV", face.autoUV);

                faceUVsJson.add(direction.name().toLowerCase(Locale.ROOT), faceJson);
            });

            json.add("faceUV", faceUVsJson);
        }

        return json;
    }

    /**
     * Creates a config for a model in JSON format.
     *
     * @param definition the model itself as {@code ModelDefinition} instance
     * @param elements   elements (parts) of a model as a {@code JsonArray} instance
     * @return           the model config as a {@code JsonObject} instance
     */
    private JsonObject createConfigJson(ModelDefinition definition, JsonArray elements) {
        var configJson = new JsonObject();
        TextureProvider skinData = definition.getTexture(TextureSheetType.SKIN, true);

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

        var firstPersonHand = Util.make(() -> {
            var json = new JsonObject();
            var left = definition.fpLeftHand;
            var right = definition.fpRightHand;

            if (left != null) {
                json.add("left", transform(left.getRPos().x, left.getRPos().y, left.getRPos().z, left.getRotationDeg().x, left.getRotationDeg().y, left.getRotationDeg().z, left.getRScale().x, left.getRScale().y, left.getRScale().z));
            } else {
                json.add("left", emptyTransform());
            }
            if (right != null) {
                json.add("right", transform(right.getRPos().x, right.getRPos().y, right.getRPos().z, right.getRotationDeg().x, right.getRotationDeg().y, right.getRotationDeg().z, right.getRScale().x, right.getRScale().y, right.getRScale().z));

            } else {
                json.add("right", emptyTransform());
            }

            return json;
        });

        configJson.addProperty("enableInvisGlow", definition.enableInvisGlow);
        configJson.addProperty("hideHeadIfSkull", definition.hideHeadIfSkull);
        configJson.addProperty("removeArmorOffset", definition.removeArmorOffset);
        configJson.addProperty("removeBedOffset", definition.removeBedOffset);
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

    private LinkedList<JsonObject> parseAnimationData(HashMap<Integer, AnimationTrigger> animationData, HashMap<Integer, Long> internalIdStoreIdMapping) {
        var resultList = new LinkedList<JsonObject>();

        animationData.forEach((i, at) -> {
            var animationJson = new JsonObject();

//            animationJson.addProperty("hidden", at.isButtonHidden());
            animationJson.addProperty("maxValue", 100);
            animationJson.addProperty("isProperty", false);
            animationJson.addProperty("interpolateVal", true);
            animationJson.addProperty("loop", at.looping);
            animationJson.addProperty("mustFinish", at.mustFinish);

            if (at.animations.get(0) instanceof Animation) {
                System.out.println("Yeash");
                var animation = (AnimationAccessor) at.animations.get(0);

                animationJson.addProperty("duration", animation.getDuration());
                animationJson.addProperty("additive", animation.getAdd());
                animationJson.addProperty("priority", animation.getPriority());
                animationJson.addProperty("interpolator", getInterpolatorName(animation.getPsfs()[0][0]));

                animationJson.add("frames", parseFrames(animation, internalIdStoreIdMapping));
            }

            if (at.valuePose != null) {
                animationJson.addProperty("pose", at.valuePose.name().toLowerCase());
                animationJson.addProperty("name", "Pasxalka");
                animationJson.addProperty("order", 0);
                animationJson.addProperty("command", false);
                animationJson.addProperty("layerControlled", true);
                animationJson.addProperty("prefix", "v");
                animationJson.addProperty("hidden", false);
            } else {
                at.onPoses.forEach((pose) -> {
//                    animationJson.addProperty("pose", "custom");
//                    animationJson.addProperty("name", ((CustomPose) pose).getName());
                    if (pose instanceof VanillaPose) {
                        animationJson.addProperty("pose", ((VanillaPose) pose).name().toLowerCase());
                        animationJson.addProperty("name", "Pasxalka");
                        animationJson.addProperty("prefix", "g");
                        animationJson.addProperty("order", 0);
                        animationJson.addProperty("command", false);
                        animationJson.addProperty("layerControlled", true);
                    } else {
                        animationJson.addProperty("pose", "custom");
                        animationJson.addProperty("name", ((CustomPose) pose).getName());
                        animationJson.addProperty("prefix", "c");
                        animationJson.addProperty("order", ((CustomPose) pose).order);
                        animationJson.addProperty("command", ((CustomPose) pose).command);
                        animationJson.addProperty("layerControlled", ((CustomPose) pose).layerCtrl);
                    }
                });
            }

            System.out.println(at.animations.get(0));
            System.out.println(at.onPoses);
            System.out.println(at.valuePose);
            System.out.println("#########");

            resultList.add(animationJson);
        });

        return resultList;
    }

    private void addAnimationsToZout(ZipOutputStream zout, LinkedList<JsonObject> animationList, Gson gson) throws IOException {
        for (JsonObject animation : animationList) {
            var uuid = UUID.randomUUID();
//            zout.putNextEntry(new ZipEntry("animations/" + animation.get("prefix").getAsString() + "_" +
//                    animation.get("pose").getAsString() + "_" + animation.get("name").toString() + "_" +
//                    uuid + ".json"));
            var prefix = animation.get("prefix").getAsString();
            var pose = animation.get("pose").getAsString();
            var name = animation.get("name").getAsString();

            switch (prefix) {
                case "v" -> zout.putNextEntry(new ZipEntry("animations/" + prefix + "_" + pose + "_" + name + "_" + uuid + ".json"));
                case "g", "c" -> zout.putNextEntry(new ZipEntry("animations/" + prefix + "_" + name + "_" + uuid + ".json"));
            }

            zout.write(gson.toJson(animation).getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }
    }

    private JsonArray parseFrames(AnimationAccessor animation, HashMap<Integer, Long> internalIdStoreIdMapping) {
        var componentIDs = animation.getComponentIDs();
        var psfs = animation.getPsfs();
        var frames = new JsonArray();

        for (int q = 0; q < animation.getFrames(); q++) {
            var frame = new JsonObject();
            var components = new JsonArray();
            for (int componentId = 0; componentId < componentIDs.length; componentId++) {
                IModelComponent component = componentIDs[componentId];
                var componentJson = new JsonObject();
                long storeId = 0;

                if (component instanceof RenderedCube rc) {
                    storeId = internalIdStoreIdMapping.get(rc.getId());
                } else if (component instanceof RootModelElement el) {
                    storeId = internalIdStoreIdMapping.get(el.getId());
                }

                componentJson.addProperty("storeID", storeId);
                componentJson.addProperty("color", String.format("%02x%02x%02x", (int) psfs[componentId][InterpolatorChannel.COLOR_R.channelID()].applyAsDouble(q), (int) psfs[componentId][InterpolatorChannel.COLOR_G.channelID()].applyAsDouble(q), (int) psfs[componentId][InterpolatorChannel.COLOR_B.channelID()].applyAsDouble(q)));
                componentJson.addProperty("show", animation.getShow()[componentId][q]);
                componentJson.add("pos", vec((float) psfs[componentId][InterpolatorChannel.POS_X.channelID()].applyAsDouble(q), (float) psfs[componentId][InterpolatorChannel.POS_Y.channelID()].applyAsDouble(q), (float) psfs[componentId][InterpolatorChannel.POS_Z.channelID()].applyAsDouble(q)));
                var x = (float) (Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_X.channelID()].applyAsDouble(q))%360 < 0 ? 360+Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_X.channelID()].applyAsDouble(q))%360 : Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_X.channelID()].applyAsDouble(q))%360);
                var y = (float) (Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_Y.channelID()].applyAsDouble(q))%360 < 0 ? 360+Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_Y.channelID()].applyAsDouble(q))%360 : Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_Y.channelID()].applyAsDouble(q))%360);
                var z = (float) (Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_Z.channelID()].applyAsDouble(q))%360 < 0 ?  360+Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_Z.channelID()].applyAsDouble(q))%360 : Math.toDegrees(psfs[componentId][InterpolatorChannel.ROT_Z.channelID()].applyAsDouble(q))%360);
                componentJson.add("rotation", vec(x, y, z));
                componentJson.add("scale", vec((float) psfs[componentId][InterpolatorChannel.SCALE_X.channelID()].applyAsDouble(q), (float) psfs[componentId][InterpolatorChannel.SCALE_Y.channelID()].applyAsDouble(q), (float) psfs[componentId][InterpolatorChannel.SCALE_Z.channelID()].applyAsDouble(q)));

                components.add(componentJson);
            }
            frame.add("components", components);

            frames.add(frame);
        }

        return frames;
    }

    private String getInterpolatorName(Interpolator inter) {
        if (inter instanceof LinearInterpolator) return "linear_single";
        if (inter instanceof LinearLoopInterpolator) return "linear_loop";
        if (inter instanceof NoInterpolate) return "no_interpolate";
        if (inter instanceof PolynomialSplineInterpolator) return "poly_single";
        if (inter instanceof PolynomialSplineLoopInterpolator) return "poly_loop";
        if (inter instanceof TrigonometricInterpolator) return "trig_single";
        if (inter instanceof TrigonometricLoopInterpolator) return "trig_loop";

        throw new IllegalArgumentException("Unknown interpolator: " + inter.getClass());
    }
}
