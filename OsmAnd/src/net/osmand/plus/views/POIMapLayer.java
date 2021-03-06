package net.osmand.plus.views;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.access.AccessibleToast;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiType;
import net.osmand.plus.ContextMenuAdapter;
import net.osmand.plus.ContextMenuAdapter.OnContextMenuClick;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.poi.PoiLegacyFilter;
import net.osmand.plus.render.RenderingIcons;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.RoutingHelper.IRouteInformationListener;
import net.osmand.plus.views.MapTextLayer.MapTextProvider;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PointF;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Toast;

public class POIMapLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider,
		MapTextProvider<Amenity>, IRouteInformationListener {
	private static final int startZoom = 10;

	public static final org.apache.commons.logging.Log log = PlatformUtil.getLog(POIMapLayer.class);

	private Paint pointAltUI;
	private Paint paintIcon;
	private Paint point;
	private OsmandMapTileView view;
	private final static int MAXIMUM_SHOW_AMENITIES = 5;

	private ResourceManager resourceManager;
	private RoutingHelper routingHelper;
	private PoiLegacyFilter filter;
	private MapTextLayer mapTextLayer;
	
	/// cache for displayed POI
	// Work with cache (for map copied from AmenityIndexRepositoryOdb)
	private MapLayerData<List<Amenity>> data;


	public POIMapLayer(final MapActivity activity) {
		routingHelper = activity.getRoutingHelper();
		routingHelper.addListener(this);
		data = new OsmandMapLayer.MapLayerData<List<Amenity>>() {
			{
				ZOOM_THRESHOLD = 0;
			}
			
			@Override
			public boolean isInterrupted() {
				return super.isInterrupted();
			}
			
			@Override
			public void layerOnPostExecute() {
				activity.getMapView().refreshMap();
			}
			
			@Override
			protected List<Amenity> calculateResult(RotatedTileBox tileBox) {
				QuadRect latLonBounds = tileBox.getLatLonBounds();
				if (filter == null) {
					return new ArrayList<Amenity>();
				}
				return resourceManager.searchAmenities(filter, latLonBounds.top, latLonBounds.left,
						latLonBounds.bottom, latLonBounds.right, tileBox.getZoom(), new ResultMatcher<Amenity>() {

							@Override
							public boolean publish(Amenity object) {
								return true;
							}

							@Override
							public boolean isCancelled() {
								return isInterrupted();
							}
						});
			}
		};
	}

	public void setFilter(PoiLegacyFilter filter) {
		this.filter = filter;
		data.clearCache();
	}

	public void getAmenityFromPoint(RotatedTileBox tb, PointF point, List<? super Amenity> am) {
		List<Amenity> objects = data.getResults();
		if (objects != null) {
			int ex = (int) point.x;
			int ey = (int) point.y;
			final int rp = getRadiusPoi(tb);
			int compare = rp;
			int radius = rp * 3 / 2;
			try {
				for (int i = 0; i < objects.size(); i++) {
					Amenity n = objects.get(i);
					int x = (int) tb.getPixXFromLatLon(n.getLocation().getLatitude(), n.getLocation().getLongitude());
					int y = (int) tb.getPixYFromLatLon(n.getLocation().getLatitude(), n.getLocation().getLongitude());
					if (Math.abs(x - ex) <= compare && Math.abs(y - ey) <= compare) {
						compare = radius;
						am.add(n);
					}
				}
			} catch (IndexOutOfBoundsException e) {
				// that's really rare case, but is much efficient than introduce synchronized block
			}
		}
	}

	@Override
	public boolean onSingleTap(PointF point, RotatedTileBox tileBox) {
		List<Amenity> am = new ArrayList<Amenity>();
		getAmenityFromPoint(tileBox, point, am);
		if (!am.isEmpty()) {
			StringBuilder res = new StringBuilder();
			for (int i = 0; i < MAXIMUM_SHOW_AMENITIES && i < am.size(); i++) {
				Amenity n = am.get(i);
				if (i > 0) {
					res.append("\n\n");
				}
				buildPoiInformation(res, n);
			}
			AccessibleToast.makeText(view.getContext(), res.toString(), Toast.LENGTH_SHORT).show();
			return true;
		}
		return false;
	}

	private StringBuilder buildPoiInformation(StringBuilder res, Amenity n) {
		String format = OsmAndFormatter.getPoiStringWithoutType(n,
				view.getSettings().usingEnglishNames());
		res.append(" " + format + "\n" + OsmAndFormatter.getAmenityDescriptionContent(view.getApplication(), n, true));
		return res;
	}

	@Override
	public void initLayer(OsmandMapTileView view) {
		this.view = view;
		pointAltUI = new Paint();
		pointAltUI.setColor(view.getApplication().getResources().getColor(R.color.poi_background));
		pointAltUI.setStyle(Style.FILL);

		paintIcon = new Paint();

		point = new Paint();
		point.setColor(Color.GRAY);
		point.setAntiAlias(true);
		point.setStyle(Style.STROKE);
		resourceManager = view.getApplication().getResourceManager();
		mapTextLayer = view.getLayerByClass(MapTextLayer.class);
	}
	

	public int getRadiusPoi(RotatedTileBox tb) {
		int r = 0;
		final double zoom = tb.getZoom();
		if (zoom < startZoom) {
			r = 0;
		} else if (zoom <= 15) {
			r = 10;
		} else if (zoom <= 16) {
			r = 14;
		} else if (zoom <= 17) {
			r = 16;
		} else {
			r = 18;
		}
		return (int) (r * tb.getDensity());
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		List<Amenity> objects = Collections.emptyList();
		if (tileBox.getZoom() >= startZoom) {
			data.queryNewData(tileBox);
			objects = data.getResults();
			if (objects != null) {
				MapPoiTypes poiTypes = view.getApplication().getPoiTypes();
				int r = getRadiusPoi(tileBox);
				for (Amenity o : objects) {
					int x = (int) tileBox.getPixXFromLatLon(o.getLocation().getLatitude(), o.getLocation()
							.getLongitude());
					int y = (int) tileBox.getPixYFromLatLon(o.getLocation().getLatitude(), o.getLocation()
							.getLongitude());
					canvas.drawCircle(x, y, r, pointAltUI);
					canvas.drawCircle(x, y, r, point);
					String id = null;
					PoiType st = o.getType().getPoiTypeByKeyName(o.getSubType());
					if (st != null) {
						if (RenderingIcons.containsIcon(st.getKeyName())) {
							id = st.getKeyName();
						} else if (RenderingIcons.containsIcon(st.getOsmTag() + "_" + st.getOsmValue())) {
							id = st.getOsmTag() + "_" + st.getOsmValue();
						} else if (RenderingIcons.containsIcon(st.getOsmValue())) {
							id = st.getOsmValue();
						}
					}
					if (id != null) {
						Bitmap bmp = RenderingIcons.getIcon(view.getContext(), id);
						if (bmp != null) {
							canvas.drawBitmap(bmp, x - bmp.getWidth() / 2, y - bmp.getHeight() / 2, paintIcon);
						}
					}
				}
			}
		}
		mapTextLayer.putData(this, objects);

	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
	}

	@Override
	public void destroyLayer() {
		routingHelper.removeListener(this);
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	@Override
	public void populateObjectContextMenu(Object o, ContextMenuAdapter adapter) {
		if (o instanceof Amenity) {
			final Amenity a = (Amenity) o;
			OnContextMenuClick listener = new ContextMenuAdapter.OnContextMenuClick() {
				@Override
				public boolean onContextMenuClick(ArrayAdapter<?> adapter, int itemId, int pos, boolean isChecked) {
					if (itemId == R.string.poi_context_menu_call) {
						try {
							Intent intent = new Intent(Intent.ACTION_VIEW);
							intent.setData(Uri.parse("tel:" + a.getPhone())); //$NON-NLS-1$
							view.getContext().startActivity(intent);
						} catch (RuntimeException e) {
							log.error("Failed to invoke call", e); //$NON-NLS-1$
							AccessibleToast.makeText(view.getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					} else if (itemId == R.string.poi_context_menu_website) {
						try {
							Intent intent = new Intent(Intent.ACTION_VIEW);
							intent.setData(Uri.parse(a.getSite()));
							view.getContext().startActivity(intent);
						} catch (RuntimeException e) {
							log.error("Failed to invoke call", e); //$NON-NLS-1$
							AccessibleToast.makeText(view.getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					} else if (itemId == R.string.poi_context_menu_showdescription) {
						showDescriptionDialog(a);
					}
					return true;
				}
			};
			if (OsmAndFormatter.getAmenityDescriptionContent(view.getApplication(), a, false).length() > 0) {
				adapter.item(R.string.poi_context_menu_showdescription)
						.iconColor(R.drawable.ic_action_note_dark).listen(listener).reg();
			}
			if (a.getPhone() != null) {
				adapter.item(R.string.poi_context_menu_call)
						.iconColor(R.drawable.ic_action_call_dark).listen(listener).reg();
			}
			if (a.getSite() != null) {
				adapter.item(R.string.poi_context_menu_website)
						.iconColor(R.drawable.ic_world_globe_dark).listen(listener)
						.reg();
			}
		}
	}

	private void showDescriptionDialog(Amenity a) {
		Builder bs = new AlertDialog.Builder(view.getContext());
		bs.setTitle(OsmAndFormatter.getPoiStringWithoutType(a, view.getSettings().usingEnglishNames()));
		if (a.getType().isWiki()) {
			bs.setMessage(a.getDescription());
		} else {
			bs.setMessage(OsmAndFormatter.getAmenityDescriptionContent(view.getApplication(), a, false));
		}
		bs.setPositiveButton(R.string.shared_string_ok, null);
		bs.show();
	}

	@Override
	public String getObjectDescription(Object o) {
		if (o instanceof Amenity) {
			return buildPoiInformation(new StringBuilder(), (Amenity) o).toString();
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if (o instanceof Amenity) {
			return new PointDescription(PointDescription.POINT_TYPE_POI, ((Amenity) o).getName()); 
		}
		return null;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, RotatedTileBox tileBox, List<Object> objects) {
		getAmenityFromPoint(tileBox, point, objects);
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if (o instanceof Amenity) {
			return ((Amenity) o).getLocation();
		}
		return null;
	}
	
	@Override
	public LatLon getTextLocation(Amenity o) {
		return o.getLocation();
	}

	@Override
	public int getTextShift(Amenity o, RotatedTileBox rb) {
		return getRadiusPoi(rb);
	}

	@Override
	public String getText(Amenity o) {
		return o.getName(view.getSettings().usingEnglishNames());
	}

	@Override
	public void newRouteIsCalculated(boolean newRoute) {
	}

	@Override
	public void routeWasCancelled() {
	}


}
