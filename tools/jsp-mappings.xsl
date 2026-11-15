<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
<xsl:output method="text" encoding="UTF-8"/><xsl:key name="servlet" match="servlet" use="servlet-name"/>
<xsl:template match="/"><xsl:for-each select="web-app/servlet-mapping"><xsl:sort select="url-pattern"/>
<xsl:value-of select="url-pattern"/><xsl:text>=</xsl:text><xsl:value-of select="key('servlet',servlet-name)/servlet-class"/><xsl:text>&#10;</xsl:text>
</xsl:for-each></xsl:template></xsl:stylesheet>
