package hex.quantile;

import hex.ModelBuilder;
import hex.ModelCategory;
import hex.schemas.ModelBuilderSchema;
import hex.schemas.QuantileV3;
import water.DKV;
import water.H2O.H2OCountedCompleter;
import water.Job;
import water.MRTask;
import water.Scope;
import water.fvec.C0DChunk;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.Log;

import java.util.Arrays;

/**
 *  Quantile model builder... building a simple QuantileModel
 */
public class Quantile extends ModelBuilder<QuantileModel,QuantileModel.QuantileParameters,QuantileModel.QuantileOutput> {
  private int _ncols;

  @Override protected boolean logMe() { return false; }

  // Called from Nano thread; start the Quantile Job on a F/J thread
  public Quantile( QuantileModel.QuantileParameters parms ) { super("Quantile",parms); init(false); }

  public ModelBuilderSchema schema() { return new QuantileV3(); }

  @Override public Quantile trainModelImpl(long work, boolean restartTimer) {
    return (Quantile)start(new QuantileDriver(), work, restartTimer);
  }

  @Override
  public long progressUnits() {
    return train().numCols()*_parms._probs.length;
  }

  @Override
  protected int desiredChunks(final Frame original_fr, boolean local) {
    return 1; //any number of chunks is fine - don't rebalance - it's not worth it for a few passes over the data (at most)
  }

  @Override public ModelCategory[] can_build() {
    return new ModelCategory[]{ModelCategory.Unknown};
  }

  /** Initialize the ModelBuilder, validating all arguments and preparing the
   *  training frame.  This call is expected to be overridden in the subclasses
   *  and each subclass will start with "super.init();".  This call is made
   *  by the front-end whenever the GUI is clicked, and needs to be fast;
   *  heavy-weight prep needs to wait for the trainModel() call.
   *
   *  Validate the probs.
   */
  @Override public void init(boolean expensive) {
    super.init(expensive);
    for( double p : _parms._probs )
      if( p < 0.0 || p > 1.0 )
        error("_probs","Probabilities must be between 0 and 1");
    _ncols = train().numCols()-numSpecialCols(); //offset/weights/nfold - should only ever be weights
    if ( numSpecialCols() == 1 && _weights == null)
      throw new IllegalArgumentException("The only special Vec that is supported for Quantiles is observation weights.");
    if ( numSpecialCols() >1 ) throw new IllegalArgumentException("Cannot handle more than 1 special vec (weights)");
  }

  // ----------------------
  private class QuantileDriver extends H2OCountedCompleter<QuantileDriver> {

    private class SumWeights extends MRTask<SumWeights> {
      double sum;
      @Override public void map(Chunk c, Chunk w) { for (int i=0;i<c.len();++i)
        if (!c.isNA(i)) {
          double wt = w.atd(i);
//          For now: let the user give small weights, results are probably not very good (same as for wtd.quantile in R)
//          if (wt > 0 && wt < 1) throw new H2OIllegalArgumentException("Quantiles only accepts weights that are either 0 or >= 1.");
          sum += wt;
        }
      }
      @Override public void reduce(SumWeights mrt) { sum+=mrt.sum; }
    }

    @Override protected void compute2() {
      QuantileModel model = null;
      try {
        Scope.enter();
        _parms.read_lock_frames(Quantile.this); // Fetch & read-lock source frame
        init(true);

        // The model to be built
        model = new QuantileModel(dest(), _parms, new QuantileModel.QuantileOutput(Quantile.this));
        model._output._parameters = _parms;
        model._output._quantiles = new double[_ncols][_parms._probs.length];
        model.delete_and_lock(_key);


        // ---
        // Run the main Quantile Loop
        Vec vecs[] = train().vecs();
        for( int n=0; n<_ncols; n++ ) {
          if( !isRunning() ) return; // Stopped/cancelled
          Vec vec = vecs[n];
          if (vec.isBad()) {
            model._output._quantiles[n] = new double[_parms._probs.length];
            Arrays.fill(model._output._quantiles[n], Double.NaN);
            continue;
          }
          double sumRows=_weights == null ? vec.length()-vec.naCnt() : new SumWeights().doAll(vec, _weights).sum;
          // Compute top-level histogram
          Histo h1 = new Histo(vec.min(),vec.max(),0,sumRows,vec.isInt());
          h1 = _weights==null ? h1.doAll(vec) : h1.doAll(vec, _weights);

          // For each probability, see if we have it exactly - or else run
          // passes until we do.
          for( int p = 0; p < _parms._probs.length; p++ ) {
            double prob = _parms._probs[p];
            Histo h = h1;  // Start from the first global histogram

            model._output._iterations++; // At least one iter per-prob-per-column
            while( Double.isNaN(model._output._quantiles[n][p] = h.findQuantile(prob,_parms._combine_method)) ) {
              h = _weights == null ? h.refinePass(prob).doAll(vec) : h.refinePass(prob).doAll(vec, _weights); // Full pass at higher resolution
              model._output._iterations++; // also count refinement iterations
            }

            // Update the model
            model.update(_key); // Update model in K/V store
            update(1);          // One unit of work
          }
          StringBuilder sb = new StringBuilder();
          sb.append("Quantile: iter: ").append(model._output._iterations).append(" Qs=").append(Arrays.toString(model._output._quantiles[n]));
          Log.debug(sb);
        }
        done();                 // Job done!
      } catch( Throwable t ) {
        Job thisJob = DKV.getGet(_key);
        if (thisJob._state == JobState.CANCELLED) {
          Log.info("Job cancelled by user.");
        } else {
          t.printStackTrace();
          failed(t);
          throw t;
        }
      } finally {
        updateModelOutput();
        if( model != null ) model.unlock(_key);
        _parms.read_unlock_frames(Quantile.this);
        Scope.exit(model == null ? null : model._key);
      }
      tryComplete();
    }
  }

  // FIXME: This is sloppy, but want to run Quantile without Job... Basically, H2O architecture is not good for this so have to hack it!
  public static class QTask extends H2OCountedCompleter<QTask> {
    final double _probs[];
    final Frame _train;
    final QuantileModel.CombineMethod _combine_method;

    public double[][] _quantiles;
    public QTask(H2OCountedCompleter cc, double[] probs, Frame train, QuantileModel.CombineMethod combine_method) {
      super(cc); _train=train; _probs=probs; _combine_method=combine_method;
    }
    @Override public void compute2() {
      // Run the main Quantile Loop
      _quantiles = new double[_train.numCols()][_probs.length];
      Vec vecs[] = _train.vecs();
      for( int n=0; n<vecs.length; n++ ) {
        Vec vec = vecs[n];
        if (vec.isBad()) {
          _quantiles[n] = new double[_probs.length];
          Arrays.fill(_quantiles[n], Double.NaN);
          continue;
        }

        // Compute top-level histogram
        Histo h1 = new Histo(vec.min(),vec.max(),0,vec.length(),vec.isInt()).doAll(vec);

        // For each probability, see if we have it exactly - or else run
        // passes until we do.
        for( int p = 0; p < _probs.length; p++ ) {
          double prob = _probs[p];
          Histo h = h1;  // Start from the first global histogram

          while( Double.isNaN(_quantiles[n][p] = h.findQuantile(prob,_combine_method)) )
            h = h.refinePass(prob).doAll(vec); // Full pass at higher resolution
        }
      }
      tryComplete();
    }
  }

  // -------------------------------------------------------------------------

  private static class Histo extends MRTask<Histo> {
    private static final int NBINS = 1024; // Default bin count
    private final int _nbins;            // Actual  bin count
    private final double _lb;            // Lower bound of bin[0]
    private final double _step;          // Step-size per-bin
    private final double _start_row;     // Starting cumulative count of weighted rows for this lower-bound
    private final double _nrows;         // Total datasets (weighted) rows
    private final boolean _isInt;        // Column only holds ints

    // Big Data output result
    double _bins[/*nbins*/];     // Weighted count of rows in each bin
    double _mins[/*nbins*/];     // Smallest element in bin
    double _maxs[/*nbins*/];     // Largest  element in bin

    private Histo(double lb, double ub, double start_row, double nrows, boolean isInt) {
      boolean is_int = (isInt && (ub - lb < NBINS));
      _nbins = is_int ? (int) (ub - lb + 1) : NBINS;
      _lb = lb;
      double ulp = Math.ulp(Math.max(Math.abs(lb), Math.abs(ub)));
      _step = is_int ? 1 : (ub + ulp - lb) / _nbins;
      _start_row = start_row;
      _nrows = nrows;
      _isInt = isInt;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("range : " + _lb + " ... " + (_lb + _nbins * _step));
      sb.append("\npsum0 : " + _start_row);
      sb.append("\ncounts: " + Arrays.toString(_bins));
      sb.append("\nmaxs  : " + Arrays.toString(_maxs));
      sb.append("\nmins  : " + Arrays.toString(_mins));
      sb.append("\n");
      return sb.toString();
    }

    @Override
    public void map(Chunk chk, Chunk weight) {
      _bins = new double[_nbins];
      _mins = new double[_nbins];
      _maxs = new double[_nbins];
      Arrays.fill(_mins, Double.MAX_VALUE);
      Arrays.fill(_maxs, -Double.MAX_VALUE);
      double d;
      for (int row = 0; row < chk._len; row++) {
        double w = weight.atd(row);
        if (w == 0) continue;
        if (!Double.isNaN(d = chk.atd(row))) {  // na.rm=true
          double idx = (d - _lb) / _step;
          if (!(0.0 <= idx && idx < _bins.length)) continue;
          int i = (int) idx;
          if (_bins[i] == 0) _mins[i] = _maxs[i] = d; // Capture unique value
          else {
            if (d < _mins[i]) _mins[i] = d;
            if (d > _maxs[i]) _maxs[i] = d;
          }
          _bins[i] += w;               // Bump row counts by row weight
        }
      }
    }

    @Override
    public void map(Chunk chk) {
      map(chk, new C0DChunk(1, chk.len()));
    }

    @Override
    public void reduce(Histo h) {
      for (int i = 0; i < _nbins; i++) { // Keep min/max
        if (_mins[i] > h._mins[i]) _mins[i] = h._mins[i];
        if (_maxs[i] < h._maxs[i]) _maxs[i] = h._maxs[i];
      }
      ArrayUtils.add(_bins, h._bins);
    }

    /** @return Quantile for probability prob, or NaN if another pass is needed. */
    double findQuantile( double prob, QuantileModel.CombineMethod method ) {
      double p2 = prob*(_nrows-1); // Desired fractional row number for this probability
      long r2 = (long)p2;
      int loidx = findBin(r2);  // Find bin holding low value
      double lo = (loidx == _nbins) ? binEdge(_nbins) : _maxs[loidx];
      if( loidx<_nbins && r2==p2 && _mins[loidx]==lo ) return lo; // Exact row number, exact bin?  Then quantile is exact

      long r3 = r2+1;
      int hiidx = findBin(r3);  // Find bin holding high value
      double hi = (hiidx == _nbins) ? binEdge(_nbins) : _mins[hiidx];
      if( loidx==hiidx )        // Somewhere in the same bin?
        return (lo==hi) ? lo : Double.NaN; // Only if bin is constant, otherwise must refine the bin
      // Split across bins - the interpolate between the hi of the lo bin, and
      // the lo of the hi bin
      return computeQuantile(lo,hi,r2,_nrows,prob,method);
    }

    private double binEdge( int idx ) { return _lb+_step*idx; }

    // bin for row; can be _nbins if just off the end (normally expect 0 to nbins-1)
    // row == position in (weighted) population
    private int findBin( double row ) {
      long sum = (long)_start_row;
      for( int i=0; i<_nbins; i++ )
        if( (long)row < (sum += _bins[i]) )
          return i;
      return _nbins;
    }

    // Run another pass over the data, with refined endpoints, to home in on
    // the exact elements for this probability.
    Histo refinePass( double prob ) {
      double prow = prob*(_nrows-1); // Desired fractional row number for this probability
      long lorow = (long)prow;       // Lower integral row number
      int loidx = findBin(lorow);    // Find bin holding low value
      // If loidx is the last bin, then high must be also the last bin - and we
      // have an exact quantile (equal to the high bin) and we didn't need
      // another refinement pass
      assert loidx < _nbins;
      double lo = _mins[loidx]; // Lower end of range to explore
      // If probability does not hit an exact row, we need the elements on
      // either side - so the next row up from the low row
      long hirow = lorow==prow ? lorow : lorow+1;
      int hiidx = findBin(hirow);    // Find bin holding high value
      // Upper end of range to explore - except at the very high end cap
      double hi = hiidx==_nbins ? binEdge(_nbins) : _maxs[hiidx];

      long sum = (long)_start_row;
      for( int i=0; i<loidx; i++ )
        sum += _bins[i];
      return new Histo(lo,hi,sum,_nrows,_isInt);
    }
  }

  /** Compute the correct final quantile from these 4 values.  If the lo and hi
   *  elements are equal, use them.  However if they differ, then there is no
   *  single value which exactly matches the desired quantile.  There are
   *  several well-accepted definitions in this case - including picking either
   *  the lo or the hi, or averaging them, or doing a linear interpolation.
   *  @param lo  the highest element less    than or equal to the desired quantile
   *  @param hi  the lowest  element greater than or equal to the desired quantile
   *  @param row row number (zero based) of the lo element; high element is +1
   *  @return desired quantile. */
  static double computeQuantile( double lo, double hi, double row, double nrows, double prob, QuantileModel.CombineMethod method ) {
    if( lo==hi ) return lo;     // Equal; pick either
    if( method == null ) method= QuantileModel.CombineMethod.INTERPOLATE;
    switch( method ) {
    case INTERPOLATE: return linearInterpolate(lo,hi,row,nrows,prob);
    case AVERAGE:     return 0.5*(hi+lo);
    case LOW:         return lo;
    case HIGH:        return hi;
    default:
      Log.info("Unknown even sample size quantile combination type: " + method + ". Doing linear interpolation.");
      return linearInterpolate(lo,hi,row,nrows,prob);
    }
  }

  private static double linearInterpolate(double lo, double hi, double row, double nrows, double prob) {
    // Unequal, linear interpolation
    double plo = (row+0)/(nrows-1); // Note that row numbers are inclusive on the end point, means we need a -1
    double phi = (row+1)/(nrows-1); // Passed in the row number for the low value, high is the next row, so +1
    assert plo <= prob && prob <= phi;
    return lo + (hi-lo)*(prob-plo)/(phi-plo); // Classic linear interpolation
  }
}
