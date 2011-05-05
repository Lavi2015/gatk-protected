package org.broadinstitute.sting.gatk.walkers.variantrecalibration;

import cern.jet.random.Normal;
import org.apache.log4j.Logger;
import org.broad.tribble.util.variantcontext.VariantContext;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.utils.MathUtils;
import org.broadinstitute.sting.utils.collections.ExpandingArrayList;
import org.broadinstitute.sting.utils.exceptions.UserException;

import java.io.PrintStream;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: rpoplin
 * Date: Mar 4, 2011
 */

public class VariantDataManager {
    private ExpandingArrayList<VariantDatum> data;
    private final double[] meanVector;
    private final double[] varianceVector; // this is really the standard deviation
    public final ArrayList<String> annotationKeys;
    private final ExpandingArrayList<TrainingSet> trainingSets;
    private final VariantRecalibratorArgumentCollection VRAC;

    protected final static Logger logger = Logger.getLogger(VariantDataManager.class);

    public VariantDataManager( final List<String> annotationKeys, final VariantRecalibratorArgumentCollection VRAC ) {
        this.data = null;
        this.annotationKeys = new ArrayList<String>( annotationKeys );
        this.VRAC = VRAC;
        meanVector = new double[this.annotationKeys.size()];
        varianceVector = new double[this.annotationKeys.size()];
        trainingSets = new ExpandingArrayList<TrainingSet>();
    }

    public void setData( final ExpandingArrayList<VariantDatum> data ) {
        this.data = data;
    }

    public ExpandingArrayList<VariantDatum> getData() {
        return data;
    }

    public void normalizeData() {
        boolean foundZeroVarianceAnnotation = false;
        for( int iii = 0; iii < meanVector.length; iii++ ) { //BUGBUG: to clean up
            final double theMean = mean(iii); //BUGBUG: to clean up
            final double theSTD = standardDeviation(theMean, iii); //BUGBUG: to clean up
            logger.info( annotationKeys.get(iii) + String.format(": \t mean = %.2f\t standard deviation = %.2f", theMean, theSTD) );
            foundZeroVarianceAnnotation = foundZeroVarianceAnnotation || (theSTD < 1E-6);
            if( annotationKeys.get(iii).toLowerCase().contains("ranksum") ) { // BUGBUG: to clean up
                for( final VariantDatum datum : data ) {
                    if( datum.annotations[iii] > 0.0 ) { datum.annotations[iii] /= 3.0; }
                }
            }
            meanVector[iii] = theMean;
            varianceVector[iii] = theSTD;
            for( final VariantDatum datum : data ) {
                datum.annotations[iii] = ( datum.isNull[iii] ? Normal.staticNextDouble(0.0, 1.0) : ( datum.annotations[iii] - theMean ) / theSTD );
                // Each data point is now [ (x - mean) / standard deviation ]
                if( annotationKeys.get(iii).toLowerCase().contains("ranksum") && datum.isNull[iii] && datum.annotations[iii] > 0.0 ) {
                    datum.annotations[iii] /= 3.0;
                }
            }
        }
        if( foundZeroVarianceAnnotation ) {
            throw new UserException.BadInput( "Found annotations with zero variance. They must be excluded before proceeding." );
        }

        // trim data by standard deviation threshold and mark failing data for exclusion later
        for( final VariantDatum datum : data ) {
            boolean remove = false;
            for( final double val : datum.annotations ) {
                remove = remove || (Math.abs(val) > VRAC.STD_THRESHOLD);
            }
            datum.failingSTDThreshold = remove;
            datum.usedForTraining = 0;
        }
    }

    public void addTrainingSet( final TrainingSet trainingSet ) {
        trainingSets.add( trainingSet );
    }

    public boolean checkHasTrainingSet() {
        for( final TrainingSet trainingSet : trainingSets ) {
            if( trainingSet.isTraining ) { return true; }
        }
        return false;
    }

    public boolean checkHasTruthSet() {
        for( final TrainingSet trainingSet : trainingSets ) {
            if( trainingSet.isTruth ) { return true; }
        }
        return false;
    }

    public boolean checkHasKnownSet() {
        for( final TrainingSet trainingSet : trainingSets ) {
            if( trainingSet.isKnown ) { return true; }
        }
        return false;
    }

    public ExpandingArrayList<VariantDatum> getTrainingData() {
        final ExpandingArrayList<VariantDatum> trainingData = new ExpandingArrayList<VariantDatum>();
        for( final VariantDatum datum : data ) {
            if( datum.atTrainingSite && !datum.failingSTDThreshold && datum.originalQual > VRAC.QUAL_THRESHOLD ) {
                trainingData.add( datum );
                datum.usedForTraining = 1;
            }
        }
        logger.info( "Training with " + trainingData.size() + " variants after standard deviation thresholding." );
        return trainingData;
    }

    public ExpandingArrayList<VariantDatum> selectWorstVariants( final double bottomPercentage ) {
        Collections.sort( data );
        final ExpandingArrayList<VariantDatum> trainingData = new ExpandingArrayList<VariantDatum>();
        final int numToAdd = Math.round((float)bottomPercentage * data.size());
        int index = 0;
        int numAdded = 0;
        while( numAdded < numToAdd ) {
            final VariantDatum datum = data.get(index++);
            if( !datum.failingSTDThreshold ) {
                trainingData.add( datum );
                datum.usedForTraining = -1;
                numAdded++;
            }
        }
        logger.info("Training with worst " + (float) bottomPercentage * 100.0f + "% of passing data --> " + trainingData.size() + " variants with LOD <= " + String.format("%.4f", data.get(index).lod) + ".");
        return trainingData;
    }

    public ExpandingArrayList<VariantDatum> getRandomDataForPlotting( int numToAdd ) {
        numToAdd = Math.min(numToAdd, data.size());
        final ExpandingArrayList<VariantDatum> returnData = new ExpandingArrayList<VariantDatum>();
        for( int iii = 0; iii < numToAdd; iii++) {
            final VariantDatum datum = data.get(GenomeAnalysisEngine.getRandomGenerator().nextInt(data.size()));
            if( !datum.failingSTDThreshold ) {
                returnData.add(datum);
            }
        }
        // add an extra 5% of points from bad training set, since that set is small but interesting
        for( int iii = 0; iii < Math.floor(0.05*numToAdd); iii++) {
            final VariantDatum datum = data.get(GenomeAnalysisEngine.getRandomGenerator().nextInt(data.size()));
            if( datum.usedForTraining == -1 && !datum.failingSTDThreshold ) { returnData.add(datum); }
            else { iii--; }
        }
                       
        return returnData;
    }

    private double mean( final int index ) {
        double sum = 0.0;
        int numNonNull = 0;
        for( final VariantDatum datum : data ) {
            if( datum.atTrainingSite && !datum.isNull[index] ) { sum += datum.annotations[index]; numNonNull++; }
        }
        return sum / ((double) numNonNull);
    }

    private double standardDeviation( final double mean, final int index ) {
        double sum = 0.0;
        int numNonNull = 0;
        for( final VariantDatum datum : data ) {
            if( datum.atTrainingSite && !datum.isNull[index] ) { sum += ((datum.annotations[index] - mean)*(datum.annotations[index] - mean)); numNonNull++; }
        }
        return Math.sqrt( sum / ((double) numNonNull) );
    }

    public void decodeAnnotations( final VariantDatum datum, final VariantContext vc, final boolean jitter ) {
        final double[] annotations = new double[annotationKeys.size()];
        final boolean[] isNull = new boolean[annotationKeys.size()];
        int iii = 0;
        for( final String key : annotationKeys ) {
            isNull[iii] = false;
            annotations[iii] = decodeAnnotation( key, vc, jitter );
            if( Double.isNaN(annotations[iii]) ) { isNull[iii] = true; }
            iii++;
        }
        datum.annotations = annotations;
        datum.isNull = isNull;
    }

    private static double decodeAnnotation( final String annotationKey, final VariantContext vc, final boolean jitter ) {
        double value;
        if( jitter && annotationKey.equalsIgnoreCase("HRUN") ) { // HRun values must be jittered a bit to work in this GMM
            value = Double.parseDouble( (String)vc.getAttribute( annotationKey ) );
            value += -0.25 + 0.5 * GenomeAnalysisEngine.getRandomGenerator().nextDouble();
        } else if( annotationKey.equals("QUAL") ) {
            value = vc.getPhredScaledQual();
        } else {
            try {
                value = Double.parseDouble( (String)vc.getAttribute( annotationKey ) );
                if(Double.isInfinite(value)) { value = Double.NaN; }
                if(annotationKey.equals("HaplotypeScore") && MathUtils.compareDoubles(value, 0.0, 0.0001) == 0 ) { value = -0.2 + 0.4*GenomeAnalysisEngine.getRandomGenerator().nextDouble(); }
            } catch( final Exception e ) {
                value = Double.NaN; // The VQSR works with missing data now by marginalizing over the missing dimension when evaluating clusters.
            }
        }
        return value;
    }

    public void parseTrainingSets( final RefMetaDataTracker tracker, final ReferenceContext ref, final AlignmentContext context, final VariantContext evalVC, final VariantDatum datum, final boolean TRUST_ALL_POLYMORPHIC ) {
        datum.isKnown = false;
        datum.atTruthSite = false;
        datum.atTrainingSite = false;
        datum.prior = 2.0;
        datum.consensusCount = 0;
        for( final TrainingSet trainingSet : trainingSets ) {
            for( final VariantContext trainVC : tracker.getVariantContexts( ref, trainingSet.name, null, context.getLocation(), false, false ) ) {
                if( trainVC != null && trainVC.isNotFiltered() && trainVC.isVariant() && ((evalVC.isSNP() && trainVC.isSNP()) || (evalVC.isIndel() && trainVC.isIndel())) && (TRUST_ALL_POLYMORPHIC || !trainVC.hasGenotypes() || trainVC.isPolymorphic()) ) {
                    datum.isKnown = datum.isKnown || trainingSet.isKnown;
                    datum.atTruthSite = datum.atTruthSite || trainingSet.isTruth;
                    datum.atTrainingSite = datum.atTrainingSite || trainingSet.isTraining;
                    datum.prior = Math.max( datum.prior, trainingSet.prior );
                    datum.consensusCount += ( trainingSet.isConsensus ? 1 : 0 );
                }
            }
        }
    }

    public void writeOutRecalibrationTable( final PrintStream RECAL_FILE ) {
        for( final VariantDatum datum : data ) {
            RECAL_FILE.println(String.format("%s,%d,%d,%.4f", datum.pos.getContig(), datum.pos.getStart(), datum.pos.getStop(), datum.lod));
        }
    }
}