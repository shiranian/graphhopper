/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper;

import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.OSMReader;
import com.graphhopper.reader.dem.CGIARProvider;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.reader.dem.SRTMProvider;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.RoutingAlgorithm;
import com.graphhopper.routing.ch.PrepareContractionHierarchies;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.*;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Easy to use access point to configure import and (offline) routing.
 * <p/>
 * @see GraphHopperAPI
 * @author Peter Karich
 */
public class GraphHopper implements GraphHopperAPI
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    // for graph:
    private GraphStorage graph;
    private EncodingManager encodingManager;
    private int defaultSegmentSize = -1;
    private String ghLocation = "";
    private DAType dataAccessType = DAType.RAM_STORE;
    private boolean sortGraph = false;
    boolean removeZipped = true;
    private boolean elevation = false;
    private LockFactory lockFactory = new NativeFSLockFactory();
    private final String fileLockName = "gh.lock";
    private boolean allowWrites = true;
    private boolean enableInstructions = true;
    private boolean fullyLoaded = false;
    // for routing
    private boolean simplifyResponse = true;
    private TraversalMode traversalMode = TraversalMode.NODE_BASED;
    // for index
    private LocationIndex locationIndex;
    private int preciseIndexResolution = 300;
    private int maxRegionSearch = 4;
    // for prepare
    private int minNetworkSize = 200;
    private int minOnewayNetworkSize = 0;
    // for CH prepare
    private AlgorithmPreparation prepare;
    private boolean doPrepare = true;
    private boolean chEnabled = true;
    private String chWeighting = "fastest";
    private int periodicUpdates = -1;
    private int lazyUpdates = -1;
    private int neighborUpdates = -1;
    private double logMessages = -1;
    // for OSM import
    private String osmFile;
    private double osmReaderWayPointMaxDistance = 1;
    private int workerThreads = -1;
    private boolean calcPoints = true;
    // utils    
    private final TranslationMap trMap = new TranslationMap().doImport();
    private ElevationProvider eleProvider = ElevationProvider.NOOP;
    private final AtomicLong visitedSum = new AtomicLong(0);

    public GraphHopper()
    {
    }

    /**
     * For testing only
     */
    protected GraphHopper loadGraph( GraphStorage g )
    {
        this.graph = g;
        fullyLoaded = true;
        initLocationIndex();
        return this;
    }

    /**
     * Specify which vehicles can be read by this GraphHopper instance. An encoding manager defines
     * how data from every vehicle is written (und read) into edges of the graph.
     */
    public GraphHopper setEncodingManager( EncodingManager em )
    {
        ensureNotLoaded();
        this.encodingManager = em;
        return this;
    }

    public EncodingManager getEncodingManager()
    {
        return encodingManager;
    }

    public GraphHopper setElevationProvider( ElevationProvider eleProvider )
    {
        if (eleProvider == null || eleProvider == ElevationProvider.NOOP)
            setElevation(false);
        else
            setElevation(true);
        this.eleProvider = eleProvider;
        return this;
    }

    /**
     * Threads for data reading.
     */
    protected int getWorkerThreads()
    {
        return workerThreads;
    }

    /**
     * Return maximum distance (in meter) to reduce points via douglas peucker while OSM import.
     */
    protected double getWayPointMaxDistance()
    {
        return osmReaderWayPointMaxDistance;
    }

    /**
     * This parameter specifies how to reduce points via douglas peucker while OSM import. Higher
     * value means more details, unit is meter. Default is 1. Disable via 0.
     */
    public GraphHopper setWayPointMaxDistance( double wayPointMaxDistance )
    {
        this.osmReaderWayPointMaxDistance = wayPointMaxDistance;
        return this;
    }

    /**
     * Sets the default traversal mode used for the algorithms and preparation.
     */
    public GraphHopper setTraversalMode( TraversalMode traversalMode )
    {
        this.traversalMode = traversalMode;
        return this;
    }

    public TraversalMode getTraversalMode()
    {
        return traversalMode;
    }

    /**
     * Configures the underlying storage to be used on a well equipped server.
     */
    public GraphHopper forServer()
    {
        // simplify to reduce network IO
        setSimplifyResponse(true);
        return setInMemory();
    }

    /**
     * Configures the underlying storage to be used on a Desktop computer with enough RAM but no
     * network latency.
     */
    public GraphHopper forDesktop()
    {
        setSimplifyResponse(false);
        return setInMemory();
    }

    /**
     * Configures the underlying storage to be used on a less powerful machine like Android and
     * Raspberry Pi with only few RAM.
     */
    public GraphHopper forMobile()
    {
        setSimplifyResponse(false);
        return setMemoryMapped();
    }

    /**
     * Precise location resolution index means also more space (disc/RAM) could be consumed and
     * probably slower query times, which would be e.g. not suitable for Android. The resolution
     * specifies the tile width (in meter).
     */
    public GraphHopper setPreciseIndexResolution( int precision )
    {
        ensureNotLoaded();
        preciseIndexResolution = precision;
        return this;
    }

    /**
     * This method call results in an in-memory graph.
     */
    public GraphHopper setInMemory()
    {
        ensureNotLoaded();
        dataAccessType = DAType.RAM_STORE;
        return this;
    }

    /**
     * Only valid option for in-memory graph and if you e.g. want to disable store on flush for unit
     * tests. Specify storeOnFlush to true if you want that existing data will be loaded FROM disc
     * and all in-memory data will be flushed TO disc after flush is called e.g. while OSM import.
     * <p>
     * @param storeOnFlush true by default
     */
    public GraphHopper setStoreOnFlush( boolean storeOnFlush )
    {
        ensureNotLoaded();
        if (storeOnFlush)
            dataAccessType = DAType.RAM_STORE;
        else
            dataAccessType = DAType.RAM;
        return this;
    }

    /**
     * Enable memory mapped configuration if not enough memory is available on the target platform.
     */
    public GraphHopper setMemoryMapped()
    {
        ensureNotLoaded();
        dataAccessType = DAType.MMAP;
        return this;
    }

    /**
     * Not yet stable enough to offer it for everyone
     */
    private GraphHopper setUnsafeMemory()
    {
        ensureNotLoaded();
        dataAccessType = DAType.UNSAFE_STORE;
        return this;
    }

    /**
     * Disables "CH-preparation". Use only if you know what you do.
     */
    public GraphHopper setDoPrepare( boolean doPrepare )
    {
        this.doPrepare = doPrepare;
        return this;
    }

    /**
     * Enables the use of contraction hierarchies to reduce query times. Enabled by default.
     * <p/>
     * @param weighting can be "fastest", "shortest" or your own weight-calculation type.
     * @see #setCHEnable(boolean)
     */
    public GraphHopper setCHWeighting( String weighting )
    {
        ensureNotLoaded();
        chWeighting = weighting;
        return this;
    }

    public String getCHWeighting()
    {
        return chWeighting;
    }

    /**
     * Enables or disables contraction hierarchies. Enabled by default.
     */
    public GraphHopper setCHEnable( boolean enable )
    {
        ensureNotLoaded();
        chEnabled = enable;
        return this;
    }

    public boolean isCHEnabled()
    {
        return chEnabled;
    }

    /**
     * @return true if storing and fetching elevation data is enabled. Default is false
     */
    public boolean hasElevation()
    {
        return elevation;
    }

    /**
     * Enable storing and fetching elevation data. Default is false
     */
    public GraphHopper setElevation( boolean includeElevation )
    {
        this.elevation = includeElevation;
        return this;
    }

    /**
     * This method specifies if the import should include way names to be able to return
     * instructions for a route.
     */
    public GraphHopper setEnableInstructions( boolean b )
    {
        ensureNotLoaded();
        enableInstructions = b;
        return this;
    }

    /**
     * This methods enables gps point calculation. If disabled only distance will be calculated.
     */
    public GraphHopper setEnableCalcPoints( boolean b )
    {
        calcPoints = b;
        return this;
    }

    /**
     * This method specifies if the returned path should be simplified or not, via douglas-peucker
     * or similar algorithm.
     */
    private GraphHopper setSimplifyResponse( boolean doSimplify )
    {
        this.simplifyResponse = doSimplify;
        return this;
    }

    /**
     * Sets the graphhopper folder.
     */
    public GraphHopper setGraphHopperLocation( String ghLocation )
    {
        ensureNotLoaded();
        if (ghLocation == null)
            throw new IllegalArgumentException("graphhopper location cannot be null");

        this.ghLocation = ghLocation;
        return this;
    }

    public String getGraphHopperLocation()
    {
        return ghLocation;
    }

    /**
     * This file can be an osm xml (.osm), a compressed xml (.osm.zip or .osm.gz) or a protobuf file
     * (.pbf).
     */
    public GraphHopper setOSMFile( String osmFileStr )
    {
        ensureNotLoaded();
        if (Helper.isEmpty(osmFileStr))
            throw new IllegalArgumentException("OSM file cannot be empty.");

        osmFile = osmFileStr;
        return this;
    }

    public String getOSMFile()
    {
        return osmFile;
    }

    /**
     * The underlying graph used in algorithms.
     * <p>
     * @throws IllegalStateException if graph is not instantiated.
     */
    public GraphStorage getGraph()
    {
        if (graph == null)
            throw new IllegalStateException("Graph not initialized");

        return graph;
    }

    public void setGraph( GraphStorage graph )
    {
        this.graph = graph;
    }

    protected void setLocationIndex( LocationIndex locationIndex )
    {
        this.locationIndex = locationIndex;
    }

    /**
     * The location index created from the graph.
     * <p>
     * @throws IllegalStateException if index is not initialized
     */
    public LocationIndex getLocationIndex()
    {
        if (locationIndex == null)
            throw new IllegalStateException("Location index not initialized");

        return locationIndex;
    }

    public AlgorithmPreparation getPreparation()
    {
        return prepare;
    }

    /**
     * Sorts the graph which requires more RAM while import. See #12
     */
    public GraphHopper setSortGraph( boolean sortGraph )
    {
        ensureNotLoaded();
        this.sortGraph = sortGraph;
        return this;
    }

    /**
     * Specifies if it is allowed for GraphHopper to write. E.g. for read only filesystems it is not
     * possible to create a lock file and so we can avoid write locks.
     */
    public GraphHopper setAllowWrites( boolean allowWrites )
    {
        this.allowWrites = allowWrites;
        return this;
    }

    public boolean isAllowWrites()
    {
        return allowWrites;
    }

    public TranslationMap getTranslationMap()
    {
        return trMap;
    }

    /**
     * Reads configuration from a CmdArgs object. Which can be manually filled, or via main(String[]
     * args) ala CmdArgs.read(args) or via configuration file ala
     * CmdArgs.readFromConfig("config.properties", "graphhopper.config")
     */
    public GraphHopper init( CmdArgs args )
    {
        args = CmdArgs.readFromConfigAndMerge(args, "config", "graphhopper.config");
        String tmpOsmFile = args.get("osmreader.osm", "");
        if (!Helper.isEmpty(tmpOsmFile))
            osmFile = tmpOsmFile;

        String graphHopperFolder = args.get("graph.location", "");
        if (Helper.isEmpty(graphHopperFolder) && Helper.isEmpty(ghLocation))
        {
            if (Helper.isEmpty(osmFile))
                throw new IllegalArgumentException("You need to specify an OSM file.");

            graphHopperFolder = Helper.pruneFileEnd(osmFile) + "-gh";
        }

        // graph
        setGraphHopperLocation(graphHopperFolder);
        defaultSegmentSize = args.getInt("graph.dataaccess.segmentSize", defaultSegmentSize);

        String graphDATypeStr = args.get("graph.dataaccess", "RAM_STORE");
        dataAccessType = DAType.fromString(graphDATypeStr);

        sortGraph = args.getBool("graph.doSort", sortGraph);
        removeZipped = args.getBool("graph.removeZipped", removeZipped);
        int bytesForFlags = args.getInt("graph.bytesForFlags", 4);
        if (args.get("graph.locktype", "native").equals("simple"))
            lockFactory = new SimpleFSLockFactory();
        else
            lockFactory = new NativeFSLockFactory();

        // elevation
        String eleProviderStr = args.get("graph.elevation.provider", "noop").toLowerCase();
        boolean eleCalcMean = args.getBool("graph.elevation.calcmean", false);
        String cacheDirStr = args.get("graph.elevation.cachedir", "");
        String baseURL = args.get("graph.elevation.baseurl", "");
        DAType elevationDAType = DAType.fromString(args.get("graph.elevation.dataaccess", "MMAP"));
        ElevationProvider tmpProvider = ElevationProvider.NOOP;
        if (eleProviderStr.equalsIgnoreCase("srtm"))
        {
            tmpProvider = new SRTMProvider();
        } else if (eleProviderStr.equalsIgnoreCase("cgiar"))
        {
            CGIARProvider cgiarProvider = new CGIARProvider();
            cgiarProvider.setAutoRemoveTemporaryFiles(args.getBool("graph.elevation.cgiar.clear", true));
            tmpProvider = cgiarProvider;
        }

        tmpProvider.setCalcMean(eleCalcMean);
        tmpProvider.setCacheDir(new File(cacheDirStr));
        if (!baseURL.isEmpty())
            tmpProvider.setBaseURL(baseURL);
        tmpProvider.setDAType(elevationDAType);
        setElevationProvider(tmpProvider);

        // optimizable prepare
        minNetworkSize = args.getInt("prepare.minNetworkSize", minNetworkSize);
        minOnewayNetworkSize = args.getInt("prepare.minOnewayNetworkSize", minOnewayNetworkSize);

        // prepare CH
        doPrepare = args.getBool("prepare.doPrepare", doPrepare);
        String tmpCHWeighting = args.get("prepare.chWeighting", "fastest");
        chEnabled = "fastest".equals(tmpCHWeighting) || "shortest".equals(tmpCHWeighting);
        if (chEnabled)
            setCHWeighting(tmpCHWeighting);

        periodicUpdates = args.getInt("prepare.updates.periodic", periodicUpdates);
        lazyUpdates = args.getInt("prepare.updates.lazy", lazyUpdates);
        neighborUpdates = args.getInt("prepare.updates.neighbor", neighborUpdates);
        logMessages = args.getDouble("prepare.logmessages", logMessages);

        // osm import
        osmReaderWayPointMaxDistance = args.getDouble("osmreader.wayPointMaxDistance", osmReaderWayPointMaxDistance);
        String flagEncoders = args.get("graph.flagEncoders", "CAR");
        if (flagEncoders.toLowerCase().contains("turncosts=true"))
            traversalMode = TraversalMode.EDGE_BASED_2DIR;
        encodingManager = new EncodingManager(flagEncoders, bytesForFlags);
        workerThreads = args.getInt("osmreader.workerThreads", workerThreads);
        enableInstructions = args.getBool("osmreader.instructions", enableInstructions);

        // index
        preciseIndexResolution = args.getInt("index.highResolution", preciseIndexResolution);
        maxRegionSearch = args.getInt("index.maxRegionSearch", maxRegionSearch);
        return this;
    }

    private void printInfo()
    {
        logger.info("version " + Constants.VERSION + "|" + Constants.BUILD_DATE + " (" + Constants.getVersions() + ")");
        if (graph != null)
            logger.info("graph " + graph.toString() + ", details:" + graph.toDetailsString());
    }

    /**
     * Imports provided data from disc and creates graph. Depending on the settings the resulting
     * graph will be stored to disc so on a second call this method will only load the graph from
     * disc which is usually a lot faster.
     */
    public GraphHopper importOrLoad()
    {
        if (!load(ghLocation))
        {
            printInfo();
            process(ghLocation);
        } else
        {
            printInfo();
        }
        return this;
    }

    /**
     * Creates the graph from OSM data.
     */
    private GraphHopper process( String graphHopperLocation )
    {
        setGraphHopperLocation(graphHopperLocation);
        Lock lock = null;
        try
        {
            if (graph.getDirectory().getDefaultType().isStoring())
            {
                lockFactory.setLockDir(new File(graphHopperLocation));
                lock = lockFactory.create(fileLockName, true);
                if (!lock.tryLock())
                    throw new RuntimeException("To avoid multiple writers we need to obtain a write lock but it failed. In " + graphHopperLocation, lock.getObtainFailedReason());
            }

            try
            {
                importData();
                graph.getProperties().put("osmreader.import.date", formatDateTime(new Date()));
            } catch (IOException ex)
            {
                throw new RuntimeException("Cannot parse OSM file " + getOSMFile(), ex);
            }
            cleanUp();
            optimize();
            postProcessing();
            flush();
        } finally
        {
            if (lock != null)
                lock.release();
        }
        return this;
    }

    protected DataReader importData() throws IOException
    {
        ensureWriteAccess();
        if (graph == null)
            throw new IllegalStateException("Load graph before importing OSM data");

        if (osmFile == null)
            throw new IllegalStateException("Couldn't load from existing folder: " + ghLocation
                    + " but also cannot import from OSM file as it wasn't specified!");

        encodingManager.setEnableInstructions(enableInstructions);
        DataReader reader = createReader(graph);
        logger.info("using " + graph.toString() + ", memory:" + Helper.getMemInfo());
        reader.readGraph();
        return reader;
    }

    protected DataReader createReader( GraphStorage tmpGraph )
    {
        return initOSMReader(new OSMReader(tmpGraph));
    }

    protected OSMReader initOSMReader( OSMReader reader )
    {
        if (osmFile == null)
            throw new IllegalArgumentException("No OSM file specified");

        logger.info("start creating graph from " + osmFile);
        File osmTmpFile = new File(osmFile);
        return reader.setOSMFile(osmTmpFile).
                setElevationProvider(eleProvider).
                setWorkerThreads(workerThreads).
                setEncodingManager(encodingManager).
                setWayPointMaxDistance(osmReaderWayPointMaxDistance);
    }

    /**
     * Opens existing graph.
     * <p/>
     * @param graphHopperFolder is the folder containing graphhopper files (which can be compressed
     * too)
     */
    @Override
    public boolean load( String graphHopperFolder )
    {
        if (Helper.isEmpty(graphHopperFolder))
            throw new IllegalStateException("graphHopperLocation is not specified. call init before");

        if (fullyLoaded)
            throw new IllegalStateException("graph is already successfully loaded");

        if (graphHopperFolder.endsWith("-gh"))
        {
            // do nothing  
        } else if (graphHopperFolder.endsWith(".osm") || graphHopperFolder.endsWith(".xml"))
        {
            throw new IllegalArgumentException("To import an osm file you need to use importOrLoad");
        } else if (!graphHopperFolder.contains("."))
        {
            if (new File(graphHopperFolder + "-gh").exists())
                graphHopperFolder += "-gh";
        } else
        {
            File compressed = new File(graphHopperFolder + ".ghz");
            if (compressed.exists() && !compressed.isDirectory())
            {
                try
                {
                    new Unzipper().unzip(compressed.getAbsolutePath(), graphHopperFolder, removeZipped);
                } catch (IOException ex)
                {
                    throw new RuntimeException("Couldn't extract file " + compressed.getAbsolutePath()
                            + " to " + graphHopperFolder, ex);
                }
            }
        }

        setGraphHopperLocation(graphHopperFolder);

        if (encodingManager == null)
            encodingManager = EncodingManager.create(ghLocation);

        if (!allowWrites && dataAccessType.isMMap())
            dataAccessType = DAType.MMAP_RO;

        GHDirectory dir = new GHDirectory(ghLocation, dataAccessType);
        if (chEnabled)
            graph = new LevelGraphStorage(dir, encodingManager, hasElevation());
        else if (encodingManager.needsTurnCostsSupport())
            graph = new GraphHopperStorage(dir, encodingManager, hasElevation(), new TurnCostStorage());
        else
            graph = new GraphHopperStorage(dir, encodingManager, hasElevation());

        graph.setSegmentSize(defaultSegmentSize);

        Lock lock = null;
        try
        {
            // create locks only if writes are allowed, if they are not allowed a lock cannot be created 
            // (e.g. on a read only filesystem locks would fail)
            if (graph.getDirectory().getDefaultType().isStoring() && isAllowWrites())
            {
                lockFactory.setLockDir(new File(ghLocation));
                lock = lockFactory.create(fileLockName, false);
                if (!lock.tryLock())
                    throw new RuntimeException("To avoid reading partial data we need to obtain the read lock but it failed. In " + ghLocation, lock.getObtainFailedReason());
            }

            if (!graph.loadExisting())
                return false;

            postProcessing();
            fullyLoaded = true;
            return true;
        } finally
        {
            if (lock != null)
                lock.release();
        }
    }

    /**
     * Sets EncodingManager, does the preparation and creates the locationIndex
     */
    protected void postProcessing()
    {
        encodingManager = graph.getEncodingManager();
        if (chEnabled)
            initCHPrepare();

        if (!isPrepared())
            prepare();
        initLocationIndex();
    }

    private boolean isPrepared()
    {
        return "true".equals(graph.getProperties().get("prepare.done"));
    }

    protected void initCHPrepare()
    {
        FlagEncoder encoder = encodingManager.getSingle();
        PrepareContractionHierarchies tmpPrepareCH = new PrepareContractionHierarchies(encoder,
                createWeighting(new WeightingMap(chWeighting), encoder), traversalMode);
        tmpPrepareCH.setPeriodicUpdates(periodicUpdates).
                setLazyUpdates(lazyUpdates).
                setNeighborUpdates(neighborUpdates).
                setLogMessages(logMessages);

        prepare = tmpPrepareCH;
        prepare.setGraph(graph);
    }

    /**
     * Based on the weightingParameters and the specified vehicle a Weighting instance can be
     * created. Note that all URL parameters are available in the weightingParameters as String if
     * you use the GraphHopper Web module.
     * <p>
     * @see Weighting.Params.create
     * @param wMap all parameters influencing the weighting. E.g. URL parameters coming via
     * GHRequest
     * @param encoder the required vehicle
     * @return the weighting to be used for route calculation
     */
    public Weighting createWeighting( WeightingMap wMap, FlagEncoder encoder )
    {
        String weighting = wMap.getWeighting();
        Weighting result;

        if ("shortest".equalsIgnoreCase(weighting))
        {
            result = new ShortestWeighting();
        } else if ("fastest".equalsIgnoreCase(weighting) || weighting.isEmpty())
        {
            if (encoder.supports(PriorityWeighting.class))
                result = new PriorityWeighting(encoder);
            else
                result = new FastestWeighting(encoder);
        } else
        {
            throw new UnsupportedOperationException("weighting " + weighting + " not supported");
        }

        if (encoder.supports(TurnWeighting.class))
        {
            result = new TurnWeighting(result, encoder, (TurnCostStorage) graph.getExtendedStorage());
        }
        return result;
    }

    @Override
    public GHResponse route( GHRequest request )
    {
        if (graph == null || !fullyLoaded)
            throw new IllegalStateException("Call load or importOrLoad before routing");

        if (graph.isClosed())
            throw new IllegalStateException("You need to create a new GraphHopper instance as it is already closed");

        GHResponse response = new GHResponse();
        List<Path> paths = getPaths(request, response);
        if (response.hasErrors())
            return response;

        enableInstructions = request.getHints().getBool("instructions", enableInstructions);
        calcPoints = request.getHints().getBool("calcPoints", calcPoints);
        double wayPointMaxDistance = request.getHints().getDouble("wayPointMaxDistance", 1d);
        Locale locale = request.getLocale();
        DouglasPeucker peucker = new DouglasPeucker().setMaxDistance(wayPointMaxDistance);

        new PathMerger().
                setCalcPoints(calcPoints).
                setDouglasPeucker(peucker).
                setEnableInstructions(enableInstructions).
                setSimplifyResponse(simplifyResponse && wayPointMaxDistance > 0).
                doWork(response, paths, trMap.getWithFallBack(locale));
        return response;
    }

    protected List<Path> getPaths( GHRequest request, GHResponse rsp )
    {
        String vehicle = request.getVehicle();
        if (vehicle.isEmpty())
            vehicle = encodingManager.getSingle().toString();

        if (!encodingManager.supports(vehicle))
        {
            rsp.addError(new IllegalArgumentException("Vehicle " + vehicle + " unsupported. "
                    + "Supported are: " + getEncodingManager()));
            return Collections.emptyList();
        }

        TraversalMode tMode;
        String tModeStr = request.getHints().get("traversal_mode", traversalMode.toString());
        try
        {
            tMode = TraversalMode.fromString(tModeStr);
        } catch (Exception ex)
        {
            rsp.addError(ex);
            return Collections.emptyList();
        }

        List<GHPoint> points = request.getPoints();
        if (points.size() < 2)
        {
            rsp.addError(new IllegalStateException("At least 2 points has to be specified, but was:" + points.size()));
            return Collections.emptyList();
        }

        FlagEncoder encoder = encodingManager.getEncoder(vehicle);
        EdgeFilter edgeFilter = new DefaultEdgeFilter(encoder);
        GHPoint startPoint = points.get(0);
        StopWatch sw = new StopWatch().start();
        QueryResult fromRes = locationIndex.findClosest(startPoint.lat, startPoint.lon, edgeFilter);
        String debug = "idLookup[0]:" + sw.stop().getSeconds() + "s";
        sw.stop();
        if (!fromRes.isValid())
        {
            rsp.addError(new IllegalArgumentException("Cannot find point 0: " + startPoint));
            return Collections.emptyList();
        }

        List<Path> paths = new ArrayList<Path>(points.size() - 1);
        for (int placeIndex = 1; placeIndex < points.size(); placeIndex++)
        {
            GHPoint point = points.get(placeIndex);
            sw = new StopWatch().start();
            QueryResult toRes = locationIndex.findClosest(point.lat, point.lon, edgeFilter);
            debug += ", [" + placeIndex + "] idLookup:" + sw.stop().getSeconds() + "s";
            if (!toRes.isValid())
            {
                rsp.addError(new IllegalArgumentException("Cannot find point " + placeIndex + ": " + point));
                break;
            }

            sw = new StopWatch().start();
            String algoStr = request.getAlgorithm().isEmpty() ? "dijkstrabi" : request.getAlgorithm();
            RoutingAlgorithm algo = null;
            if (chEnabled)
            {
                if (prepare == null)
                    throw new IllegalStateException("Preparation object is null. CH-preparation wasn't done or did you "
                            + "forget to call setCHEnable(false)?");

                if (algoStr.equals("dijkstrabi"))
                    algo = prepare.createAlgo();
                else if (algoStr.equals("astarbi"))
                    algo = ((PrepareContractionHierarchies) prepare).createAStar();
                else
                {
                    rsp.addError(new IllegalStateException(
                            "Only dijkstrabi and astarbi is supported for LevelGraph (using contraction hierarchies)!"));
                    break;
                }
            } else
            {
                Weighting weighting = createWeighting(request.getHints(), encoder);
                prepare = NoOpAlgorithmPreparation.createAlgoPrepare(graph, algoStr, encoder, weighting, tMode);
                algo = prepare.createAlgo();
            }

            debug += ", algoInit:" + sw.stop().getSeconds() + "s";
            sw = new StopWatch().start();

            Path path = algo.calcPath(fromRes, toRes);
            if (path.getMillis() < 0)
                throw new RuntimeException("Time was negative. Please report as bug and include:" + request);

            paths.add(path);
            debug += ", " + algo.getName() + "-routing:" + sw.stop().getSeconds() + "s, " + path.getDebugInfo();
            visitedSum.addAndGet(algo.getVisitedNodes());
            fromRes = toRes;
        }

        if (rsp.hasErrors())
            return Collections.emptyList();

        if (points.size() - 1 != paths.size())
            throw new RuntimeException("There should be exactly one more places than paths. places:" + points.size() + ", paths:" + paths.size());

        rsp.setDebugInfo(debug);
        return paths;
    }

    protected LocationIndex createLocationIndex( Directory dir )
    {
        LocationIndex tmpIndex;
        if (graph instanceof LevelGraph)
        {
            tmpIndex = new LocationIndexTreeSC((LevelGraph) graph, dir);
        } else
        {
            tmpIndex = new LocationIndexTree(graph, dir);
        }
        tmpIndex.setResolution(preciseIndexResolution);
        ((LocationIndexTree) tmpIndex).setMaxRegionSearch(maxRegionSearch);

        if (!tmpIndex.loadExisting())
        {
            ensureWriteAccess();
            tmpIndex.prepareIndex();
        }

        return tmpIndex;
    }

    /**
     * Initializes the location index. Currently this has to be done after the ch-preparation!
     * Because - to improve performance - certain edges won't be available in a ch-graph and the
     * index needs to know this and selects the correct nodes which still see the correct neighbors.
     * See #116
     */
    protected void initLocationIndex()
    {
        if (locationIndex != null)
            throw new IllegalStateException("Cannot initialize locationIndex twice!");

        locationIndex = createLocationIndex(graph.getDirectory());
    }

    protected void optimize()
    {
        logger.info("optimizing ... (" + Helper.getMemInfo() + ")");
        graph.optimize();
        logger.info("finished optimize (" + Helper.getMemInfo() + ")");

        // Later: move this into the GraphStorage.optimize method
        // Or: Doing it after preparation to optimize shortcuts too. But not possible yet #12
        if (sortGraph)
        {
            if (graph instanceof LevelGraph && isPrepared())
                throw new IllegalArgumentException("Sorting prepared LevelGraph is not possible yet. See #12");

            GraphStorage newGraph = GHUtility.newStorage(graph);
            GHUtility.sortDFS(graph, newGraph);
            logger.info("graph sorted (" + Helper.getMemInfo() + ")");
            graph = newGraph;
        }
    }

    protected void prepare()
    {
        boolean tmpPrepare = doPrepare && prepare != null;
        if (tmpPrepare)
        {
            ensureWriteAccess();
            if (prepare instanceof PrepareContractionHierarchies && encodingManager.getVehicleCount() > 1)
                throw new IllegalArgumentException("Contraction hierarchies preparation "
                        + "requires (at the moment) only one vehicle. But was:" + encodingManager);

            logger.info("calling prepare.doWork for " + encodingManager.toString() + " ... (" + Helper.getMemInfo() + ")");
            prepare.doWork();
            graph.getProperties().put("prepare.date", formatDateTime(new Date()));
        }
        graph.getProperties().put("prepare.done", tmpPrepare);
    }

    protected void cleanUp()
    {
        int prev = graph.getNodes();
        PrepareRoutingSubnetworks preparation = new PrepareRoutingSubnetworks(graph, encodingManager);
        preparation.setMinNetworkSize(minNetworkSize);
        preparation.setMinOnewayNetworkSize(this.minOnewayNetworkSize);
        logger.info("start finding subnetworks, " + Helper.getMemInfo());
        preparation.doWork();
        int n = graph.getNodes();
        // calculate remaining subnetworks
        int remainingSubnetworks = preparation.findSubnetworks().size();
        logger.info("edges: " + graph.getAllEdges().getMaxId() + ", nodes " + n + ", there were " + preparation.getSubNetworks()
                + " subnetworks. removed them => " + (prev - n) + " less nodes. Remaining subnetworks:" + remainingSubnetworks);
    }

    protected void flush()
    {
        logger.info("flushing graph " + graph.toString() + ", details:" + graph.toDetailsString() + ", " + Helper.getMemInfo() + ")");
        graph.flush();
        fullyLoaded = true;
    }

    /**
     * Releases all associated resources like memory or files. But it does not remove them. To
     * remove the files created in graphhopperLocation you have to call clean().
     */
    public void close()
    {
        if (graph != null)
            graph.close();

        if (locationIndex != null)
            locationIndex.close();

        try
        {
            lockFactory.forceRemove(fileLockName, true);
        } catch (Exception ex)
        {
            // silently fail e.g. on Windows where we cannot remove an unreleased native lock
        }
    }

    /**
     * Removes the on-disc routing files. Call only after calling close or before importOrLoad or
     * load
     */
    public void clean()
    {
        if (getGraphHopperLocation().isEmpty())
            throw new IllegalStateException("Cannot clean GraphHopper without specified graphHopperLocation");

        File folder = new File(getGraphHopperLocation());
        Helper.removeDir(folder);
    }

    // make sure this is identical to buildDate used in pom.xml
    // <maven.build.timestamp.format>yyyy-MM-dd'T'HH:mm:ssZ</maven.build.timestamp.format>
    private String formatDateTime( Date date )
    {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(date);
    }

    protected void ensureNotLoaded()
    {
        if (fullyLoaded)
            throw new IllegalStateException("No configuration changes are possible after loading the graph");
    }

    protected void ensureWriteAccess()
    {
        if (!allowWrites)
            throw new IllegalStateException("Writes are not allowed!");
    }

    /**
     * Returns the current sum of the visited nodes while routing. Mainly for statistic and
     * debugging purposes.
     */
    public long getVisitedSum()
    {
        return visitedSum.get();
    }
}
