project(
  'auxen',
  version: '0.1.0',
  meson_version: '>= 0.62.0',
)

python = import('python')
py_installation = python.find_installation('python3')

# Install the Python package
py_installation.install_sources(
  files([
    'auxen/__init__.py',
    'auxen/app.py',
    'auxen/db.py',
    'auxen/player.py',
    'auxen/queue.py',
    'auxen/models.py',
    'auxen/matching.py',
    'auxen/mpris.py',
    'auxen/lyrics.py',
    'auxen/equalizer.py',
    'auxen/sleep_timer.py',
    'auxen/notifications.py',
    'auxen/favorites_sync.py',
    'auxen/smart_playlists.py',
    'auxen/crossfade.py',
    'auxen/album_art.py',
    'auxen/m3u.py',
    'auxen/lastfm.py',
  ]),
  subdir: 'auxen',
)

py_installation.install_sources(
  files([
    'auxen/providers/__init__.py',
    'auxen/providers/base.py',
    'auxen/providers/local.py',
    'auxen/providers/tidal.py',
  ]),
  subdir: 'auxen/providers',
)

py_installation.install_sources(
  files([
    'auxen/views/__init__.py',
    'auxen/views/album_detail.py',
    'auxen/views/artist_detail.py',
    'auxen/views/context_menu.py',
    'auxen/views/equalizer_dialog.py',
    'auxen/views/explore.py',
    'auxen/views/favorites.py',
    'auxen/views/home.py',
    'auxen/views/library.py',
    'auxen/views/lyrics_panel.py',
    'auxen/views/mini_player.py',
    'auxen/views/mixes.py',
    'auxen/views/now_playing.py',
    'auxen/views/playlist_view.py',
    'auxen/views/queue_panel.py',
    'auxen/views/search.py',
    'auxen/views/settings.py',
    'auxen/views/sidebar.py',
    'auxen/views/sleep_timer_dialog.py',
    'auxen/views/smart_playlist_view.py',
    'auxen/views/stats.py',
  ]),
  subdir: 'auxen/views',
)

# Install entry point script
install_data(
  'data/auxen',
  install_dir: get_option('bindir'),
  install_mode: 'rwxr-xr-x',
)

# Install desktop file
install_data(
  'data/io.github.auxen.Auxen.desktop',
  install_dir: get_option('datadir') / 'applications',
)

# Install AppStream metadata
install_data(
  'data/io.github.auxen.Auxen.metainfo.xml',
  install_dir: get_option('datadir') / 'metainfo',
)

# Install icon
install_data(
  'data/icons/hicolor/scalable/apps/io.github.auxen.Auxen.svg',
  install_dir: get_option('datadir') / 'icons' / 'hicolor' / 'scalable' / 'apps',
)

# Install CSS
install_data(
  'data/style.css',
  install_dir: get_option('datadir') / 'auxen',
)
