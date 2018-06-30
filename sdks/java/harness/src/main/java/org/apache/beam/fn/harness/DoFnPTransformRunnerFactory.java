/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.fn.harness;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.beam.fn.harness.control.BundleSplitListener;
import org.apache.beam.fn.harness.data.BeamFnDataClient;
import org.apache.beam.fn.harness.state.BeamFnStateClient;
import org.apache.beam.fn.harness.state.SideInputSpec;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.PCollection;
import org.apache.beam.model.pipeline.v1.RunnerApi.PTransform;
import org.apache.beam.model.pipeline.v1.RunnerApi.ParDoPayload;
import org.apache.beam.runners.core.construction.PCollectionViewTranslation;
import org.apache.beam.runners.core.construction.ParDoTranslation;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.fn.data.FnDataReceiver;
import org.apache.beam.sdk.fn.function.ThrowingRunnable;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Materializations;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowedValue.WindowedValueCoder;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowingStrategy;

/** A {@link PTransformRunnerFactory} for transforms invoking a {@link DoFn}. */
abstract class DoFnPTransformRunnerFactory<
        TransformInputT,
        FnInputT,
        OutputT,
        RunnerT extends DoFnPTransformRunnerFactory.DoFnPTransformRunner<TransformInputT>>
    implements PTransformRunnerFactory<RunnerT> {
  interface DoFnPTransformRunner<T> {
    void startBundle() throws Exception;

    void processElement(WindowedValue<T> input) throws Exception;

    void finishBundle() throws Exception;
  }

  @Override
  public final RunnerT createRunnerForPTransform(
      PipelineOptions pipelineOptions,
      BeamFnDataClient beamFnDataClient,
      BeamFnStateClient beamFnStateClient,
      String ptransformId,
      PTransform pTransform,
      Supplier<String> processBundleInstructionId,
      Map<String, PCollection> pCollections,
      Map<String, RunnerApi.Coder> coders,
      Map<String, RunnerApi.WindowingStrategy> windowingStrategies,
      Multimap<String, FnDataReceiver<WindowedValue<?>>> pCollectionIdsToConsumers,
      Consumer<ThrowingRunnable> addStartFunction,
      Consumer<ThrowingRunnable> addFinishFunction,
      BundleSplitListener splitListener) {
    Context<FnInputT, OutputT> context =
        new Context<>(
            pipelineOptions,
            beamFnStateClient,
            ptransformId,
            pTransform,
            processBundleInstructionId,
            pCollections,
            coders,
            windowingStrategies,
            pCollectionIdsToConsumers,
            splitListener);

    RunnerT runner = createRunner(context);

    // Register the appropriate handlers.
    addStartFunction.accept(runner::startBundle);
    Iterable<String> mainInput =
        Sets.difference(
            pTransform.getInputsMap().keySet(), context.parDoPayload.getSideInputsMap().keySet());
    for (String localInputName : mainInput) {
      pCollectionIdsToConsumers.put(
          pTransform.getInputsOrThrow(localInputName),
          (FnDataReceiver) (FnDataReceiver<WindowedValue<TransformInputT>>) runner::processElement);
    }
    addFinishFunction.accept(runner::finishBundle);
    return runner;
  }

  abstract RunnerT createRunner(Context<FnInputT, OutputT> context);

  static class Context<InputT, OutputT> {
    final PipelineOptions pipelineOptions;
    final BeamFnStateClient beamFnStateClient;
    final String ptransformId;
    final PTransform pTransform;
    final Supplier<String> processBundleInstructionId;
    final RehydratedComponents rehydratedComponents;
    final DoFn<InputT, OutputT> doFn;
    final TupleTag<OutputT> mainOutputTag;
    final Coder<?> inputCoder;
    final Coder<?> keyCoder;
    final Coder<? extends BoundedWindow> windowCoder;
    final WindowingStrategy<InputT, ?> windowingStrategy;
    final Map<TupleTag<?>, SideInputSpec> tagToSideInputSpecMap;
    Map<TupleTag<?>, Coder<?>> outputCoders;
    final ParDoPayload parDoPayload;
    final ListMultimap<TupleTag<?>, FnDataReceiver<WindowedValue<?>>> tagToConsumer;
    final BundleSplitListener splitListener;

    Context(
        PipelineOptions pipelineOptions,
        BeamFnStateClient beamFnStateClient,
        String ptransformId,
        PTransform pTransform,
        Supplier<String> processBundleInstructionId,
        Map<String, PCollection> pCollections,
        Map<String, RunnerApi.Coder> coders,
        Map<String, RunnerApi.WindowingStrategy> windowingStrategies,
        Multimap<String, FnDataReceiver<WindowedValue<?>>> pCollectionIdsToConsumers,
        BundleSplitListener splitListener) {
      this.pipelineOptions = pipelineOptions;
      this.beamFnStateClient = beamFnStateClient;
      this.ptransformId = ptransformId;
      this.pTransform = pTransform;
      this.processBundleInstructionId = processBundleInstructionId;
      ImmutableMap.Builder<TupleTag<?>, SideInputSpec> tagToSideInputSpecMapBuilder =
          ImmutableMap.builder();
      try {
        rehydratedComponents =
            RehydratedComponents.forComponents(
                RunnerApi.Components.newBuilder()
                    .putAllCoders(coders)
                    .putAllWindowingStrategies(windowingStrategies)
                    .build());
        parDoPayload = ParDoPayload.parseFrom(pTransform.getSpec().getPayload());
        doFn = (DoFn) ParDoTranslation.getDoFn(parDoPayload);
        mainOutputTag = (TupleTag) ParDoTranslation.getMainOutputTag(parDoPayload);
        String mainInputTag =
            Iterables.getOnlyElement(
                Sets.difference(
                    pTransform.getInputsMap().keySet(), parDoPayload.getSideInputsMap().keySet()));
        PCollection mainInput = pCollections.get(pTransform.getInputsOrThrow(mainInputTag));
        inputCoder = rehydratedComponents.getCoder(mainInput.getCoderId());
        if (inputCoder instanceof KvCoder
            // TODO: Stop passing windowed value coders within PCollections.
            || (inputCoder instanceof WindowedValue.WindowedValueCoder
                && (((WindowedValueCoder) inputCoder).getValueCoder() instanceof KvCoder))) {
          this.keyCoder =
              inputCoder instanceof WindowedValueCoder
                  ? ((KvCoder) ((WindowedValueCoder) inputCoder).getValueCoder()).getKeyCoder()
                  : ((KvCoder) inputCoder).getKeyCoder();
        } else {
          this.keyCoder = null;
        }

        windowingStrategy =
            (WindowingStrategy)
                rehydratedComponents.getWindowingStrategy(mainInput.getWindowingStrategyId());
        windowCoder = windowingStrategy.getWindowFn().windowCoder();

        outputCoders = Maps.newHashMap();
        for (Map.Entry<String, String> entry : pTransform.getOutputsMap().entrySet()) {
          TupleTag<?> outputTag = new TupleTag<>(entry.getKey());
          RunnerApi.PCollection outputPCollection = pCollections.get(entry.getValue());
          Coder<?> outputCoder = rehydratedComponents.getCoder(outputPCollection.getCoderId());
          outputCoders.put(outputTag, outputCoder);
        }

        // Build the map from tag id to side input specification
        for (Map.Entry<String, RunnerApi.SideInput> entry :
            parDoPayload.getSideInputsMap().entrySet()) {
          String sideInputTag = entry.getKey();
          RunnerApi.SideInput sideInput = entry.getValue();
          checkArgument(
              Materializations.MULTIMAP_MATERIALIZATION_URN.equals(
                  sideInput.getAccessPattern().getUrn()),
              "This SDK is only capable of dealing with %s materializations "
                  + "but was asked to handle %s for PCollectionView with tag %s.",
              Materializations.MULTIMAP_MATERIALIZATION_URN,
              sideInput.getAccessPattern().getUrn(),
              sideInputTag);

          PCollection sideInputPCollection =
              pCollections.get(pTransform.getInputsOrThrow(sideInputTag));
          WindowingStrategy sideInputWindowingStrategy =
              rehydratedComponents.getWindowingStrategy(
                  sideInputPCollection.getWindowingStrategyId());
          tagToSideInputSpecMapBuilder.put(
              new TupleTag<>(entry.getKey()),
              SideInputSpec.create(
                  rehydratedComponents.getCoder(sideInputPCollection.getCoderId()),
                  sideInputWindowingStrategy.getWindowFn().windowCoder(),
                  PCollectionViewTranslation.viewFnFromProto(entry.getValue().getViewFn()),
                  PCollectionViewTranslation.windowMappingFnFromProto(
                      entry.getValue().getWindowMappingFn())));
        }
      } catch (IOException exn) {
        throw new IllegalArgumentException("Malformed ParDoPayload", exn);
      }

      ImmutableListMultimap.Builder<TupleTag<?>, FnDataReceiver<WindowedValue<?>>>
          tagToConsumerBuilder = ImmutableListMultimap.builder();
      for (Map.Entry<String, String> entry : pTransform.getOutputsMap().entrySet()) {
        tagToConsumerBuilder.putAll(
            new TupleTag<>(entry.getKey()), pCollectionIdsToConsumers.get(entry.getValue()));
      }
      tagToConsumer = tagToConsumerBuilder.build();
      tagToSideInputSpecMap = tagToSideInputSpecMapBuilder.build();
      this.splitListener = splitListener;
    }
  }
}
