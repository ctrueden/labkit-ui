
package net.imglib2.labkit.segmentation;

import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.labkit.bdv.BdvLayer;
import net.imglib2.labkit.bdv.BdvShowable;
import net.imglib2.labkit.models.DefaultSegmentationModel;
import net.imglib2.labkit.models.Holder;
import net.imglib2.labkit.models.ImageLabelingModel;
import net.imglib2.labkit.models.MappedHolder;
import net.imglib2.labkit.models.SegmentationResultsModel;
import net.imglib2.labkit.utils.Notifier;
import net.imglib2.labkit.utils.RandomAccessibleContainer;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.type.volatiles.VolatileShortType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.view.Views;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class PredictionLayer implements BdvLayer {

	private final Holder<SegmentationResultsModel> model;
	private final RandomAccessibleContainer<VolatileARGBType> segmentationContainer;
	private final SharedQueue queue = new SharedQueue(Runtime.getRuntime()
		.availableProcessors());
	private final Holder<Boolean> visibility;
	private Notifier listeners = new Notifier();
	private RandomAccessibleInterval<? extends NumericType<?>> view;
	private AffineTransform3D transformation;
	private Set<SegmentationResultsModel> alreadyRegistered = Collections.newSetFromMap(
		new WeakHashMap<>());

	public static PredictionLayer createPredictionLayer(DefaultSegmentationModel segmentationModel) {
		ImageLabelingModel imageLabelingModel = segmentationModel.imageLabelingModel();
		return new PredictionLayer(
			new MappedHolder<>(segmentationModel.segmenterList().selectedSegmenter(), si -> si == null
				? null : si.results(imageLabelingModel)),
			segmentationModel.segmenterList().segmentationVisibility(),
			imageLabelingModel.labelTransformation(),
			imageLabelingModel.labeling().get().interval());
	}

	private PredictionLayer(
		Holder<SegmentationResultsModel> model,
		Holder<Boolean> visibility,
		AffineTransform3D transformation,
		Interval interval)
	{
		this.model = model;
		this.segmentationContainer = new RandomAccessibleContainer<>(getEmptyPrediction(interval
			.numDimensions()));
		this.transformation = transformation;
		this.view = Views.interval(segmentationContainer, interval);
		this.visibility = visibility;
		model.notifier().add(() -> classifierChanged());
		registerListener(model.get());
		classifierChanged();
	}

	private void registerListener(SegmentationResultsModel segmenter) {
		if (segmenter == null) return;
		if (alreadyRegistered.contains(segmenter)) return;
		alreadyRegistered.add(segmenter);
		segmenter.segmentationChangedListeners().add(
			() -> onTrainingCompleted(segmenter));
	}

	private void onTrainingCompleted(SegmentationResultsModel segmenter) {
		if (model.get() == segmenter) {
			classifierChanged();
			visibility.set(true);
		}
	}

	private RandomAccessible<VolatileARGBType> getEmptyPrediction(int numDimensions) {
		return ConstantUtils.constantRandomAccessible(new VolatileARGBType(0), numDimensions);
	}

	private void classifierChanged() {
		SegmentationResultsModel results = model.get();
		registerListener(results);
		boolean hasResult = results != null && results.hasResults();
		RandomAccessible<VolatileARGBType> source = hasResult ? Views.extendValue(coloredVolatileView(
			results), new VolatileARGBType(0)) : getEmptyPrediction(2);
		segmentationContainer.setSource(source);
		listeners.notifyListeners();
	}

	private RandomAccessibleInterval<VolatileARGBType> coloredVolatileView(
		SegmentationResultsModel selected)
	{
		ARGBType[] colors = selected.colors().toArray(new ARGBType[0]);
		return mapColors(colors, VolatileViews.wrapAsVolatile(selected
			.segmentation(), queue));
	}

	private RandomAccessibleInterval<VolatileARGBType> mapColors(
		ARGBType[] colors, RandomAccessibleInterval<VolatileShortType> source)
	{
		final Converter<VolatileShortType, VolatileARGBType> conv = (input,
			output) -> {
			final boolean isValid = input.isValid();
			output.setValid(isValid);
			if (isValid) output.set(colors[input.get().get()].get());
		};

		return Converters.convert(source, conv, new VolatileARGBType());
	}

	@Override
	public BdvShowable image() {
		return BdvShowable.wrap(view, transformation);
	}

	@Override
	public Notifier listeners() {
		return listeners;
	}

	@Override
	public Holder<Boolean> visibility() {
		return visibility;
	}

	@Override
	public String title() {
		return "Segmentation";
	}
}
