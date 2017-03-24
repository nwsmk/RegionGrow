package com.nwsmk.android.regiongrow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static com.nwsmk.android.regiongrow.Utils.mean;
import static com.nwsmk.android.regiongrow.Utils.sum2D;
import static java.lang.Math.abs;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {

    private static final String TAG = "REGION GROW APP";
    private static final String filename = "/mnt/sdcard/Pictures/tmp_map.png";

    private GoogleMap mMap;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {

        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i("OpenCV", "OpenCV loaded successfully");
                }
                break;

                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_2_0, this, mLoaderCallback);
        } else {
            Log.d("OpenCV", "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }


    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);

        /** Attached map long press listener */
        mMap.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(LatLng latLng) {

                // add marker
                mMap.addMarker(new MarkerOptions().position(latLng));

                final Point seed = getPixelFromLatLng(latLng);

                // save map image
                GoogleMap.SnapshotReadyCallback callback = new GoogleMap.SnapshotReadyCallback() {
                    @Override
                    public void onSnapshotReady(Bitmap bitmap) {

                        try {
                            FileOutputStream out = new FileOutputStream(filename);
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
                            out.flush();
                            out.close();

                            // region grow
                            Log.e(TAG, "Region ");
                            grow(seed);

                        } catch (IOException ioe) {
                            Log.e(TAG, "Error writing image file to local storage.");
                        }

                    }
                };
                mMap.snapshot(callback);
            }
        });


        // Add a marker in Sydney and move the camera
        /** LatLng sydney = new LatLng(-34, 151);
        mMap.addMarker(new MarkerOptions().position(sydney).title("Marker in Sydney"));
        mMap.moveCamera(CameraUpdateFactory.newLatLng(sydney)); */
    }


    /** Get pixel coordinates from lat/lng input */
    private Point getPixelFromLatLng(LatLng latLng) {
        android.graphics.Point pixelPoint = mMap.getProjection().toScreenLocation(latLng);
        Point openCVPoint = new Point(pixelPoint.x, pixelPoint.y);

        return openCVPoint;
    }

    /** Region grow */
    private void grow(Point input_point) {

        // load image
        try {
            InputStream in = new FileInputStream(filename);

            // process image
            Bitmap originalBmp = BitmapFactory.decodeStream(in);

            int num_rows = originalBmp.getHeight();
            int num_cols = originalBmp.getWidth();

            Mat originalMat = new Mat(num_rows,
                    num_cols,
                    CvType.CV_8UC4,
                    new Scalar(0));
            Utils.bitmapToMat(originalBmp, originalMat);

            /** region grow */
            // edge
            int edge_index    = 1;
            Point[] edge_list = new Point[num_rows*num_cols];
            edge_list[edge_index-1] = input_point;

            // neighbor
            int neigh_index    = 0;
            Point[] neigh_list = new Point[num_rows*num_cols];
            int[][] neigh_mask = new int[4][2];
            neigh_mask[0][0] =  0; neigh_mask[0][1] = -1;
            neigh_mask[1][0] =  0; neigh_mask[1][1] =  1;
            neigh_mask[2][0] =  1; neigh_mask[2][1] =  0;
            neigh_mask[3][0] = -1; neigh_mask[3][1] =  0;

            // region
            int inx = (int) input_point.x;
            int iny = (int) input_point.y;

            double mean_region = mean(originalMat.get(iny, inx));
            int num_members = 1;

            // flags
            int[][] img_flag = new int[num_rows][num_cols];

            // output
            int[][] img_out = new int[num_rows][num_cols];


            int currx = inx;
            int curry = iny;

            while ((sum2D(img_flag) < (num_rows*num_cols)) && (currx > 10) && (curry > 10) && (currx < (num_cols-10)) && (curry < (num_rows-10)) && (edge_index > 0)) {

                // process all edge members
                int total_edge = edge_index;
                for (int k = 0; k < total_edge; k++) {

                    edge_index = edge_index - 1;
                    Point edge = edge_list[edge_index];

                    // find all neighbors of current edge
                    for (int j = 0; j < neigh_mask.length; j++) {

                        double x = edge.x + neigh_mask[j][0];
                        double y = edge.y + neigh_mask[j][1];

                        Point new_neighbor = new Point(x, y);

                        int intx = (int) x;
                        int inty = (int) y;

                        currx = intx;
                        curry = inty;

                        // only add unprocessed neighbors to neighbor list
                        if (img_flag[inty][intx] == 0) {
                            img_flag[inty][intx] = 1;

                            // increase number of neighbor by one
                            neigh_list[neigh_index] = new_neighbor;
                            neigh_index = neigh_index + 1;
                        }

                    }

                    // clear processed edge list
                    edge_list[edge_index] = new Point(0, 0);
                }

                // process neighbors to get new edge
                int total_neigh = neigh_index;
                for (int j = 0; j < total_neigh; j++) {

                    // check if neighbor is in the region
                    // if YES, add this neighbor as a new edge
                    neigh_index = neigh_index - 1;
                    Point index = neigh_list[neigh_index];
                    int x = (int) index.x;
                    int y = (int) index.y;
                    double[] px = originalMat.get(y, x);

                    double mean_px = com.nwsmk.android.regiongrow.Utils.mean(px);
                    double mean_diff = abs(mean_px-mean_region);

                    Log.d("Mean Diff = ", Double.toString(mean_diff));

                    if (mean_diff < 0.1) {

                        // update region mean
                        mean_region = ((1*mean_px) + (num_members*mean_region)) / (1 + num_members);
                        num_members++;

                        // update output
                        Point out_index = neigh_list[neigh_index];
                        int outx = (int) out_index.x;
                        int outy = (int) out_index.y;

                        img_out[outy][outx] = 2;

                        // update edge index
                        edge_list[edge_index] = neigh_list[neigh_index];
                        edge_index = edge_index + 1;
                    }

                    // clear processed neighbor
                    neigh_list[neigh_index] = new Point(0, 0);
                }

                Log.d(TAG, Double.toString(sum2D(img_out)));
            }

            // finish region growing
            Log.d(TAG, "END OF PROCESS");

            // see output image
            Mat out_mat = new Mat(num_rows, num_cols, CvType.CV_8U, new Scalar(0));
            for (int i = 0; i < num_rows; i++) {
                for (int j = 0; j < num_cols; j++) {
                    if (img_out[i][j] == 2) {
                        out_mat.put(i, j, 255);
                    }
                }
            }

            Bitmap outBmp = originalBmp.copy(Bitmap.Config.ARGB_8888, false);
            Utils.matToBitmap(out_mat, outBmp);

            out_mat.size();

        } catch (IOException ioe) {
            Log.e(TAG, "Error reading image file from local storage.");
        }
    }
}
