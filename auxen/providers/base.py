"""Abstract base class for content providers."""

from abc import ABC, abstractmethod

from ..models import Track


class ContentProvider(ABC):
    """Interface that every music source (local files, Tidal, etc.) must implement."""

    @abstractmethod
    def search(self, query: str) -> list[Track]:
        """Return tracks matching *query*."""
        ...

    @abstractmethod
    def get_stream_uri(self, track: Track) -> str:
        """Return a playable URI for *track*."""
        ...
