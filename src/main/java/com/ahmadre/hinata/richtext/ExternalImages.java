package com.ahmadre.hinata.richtext;

/**
 * What a stored text does with an image on another server (HIN-181).
 *
 * <p>An issue's description and comments keep it: the image is never fetched by a reader, only
 * by this server on a reader's behalf through the issue's image proxy, and only once that reader
 * chose to load it. Every other text (a knowledge article, a project template) has no such proxy,
 * so there the image stays what HIN-177 made it, a link that says what was here.
 */
public enum ExternalImages {

	/** The image node stays as it is; readers load it through the issue's proxy. */
	KEEP,

	/** The image becomes a link to its address, labelled "External image". */
	LINK
}
