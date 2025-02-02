/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package dunit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.AvailablePort.Keeper;

/**
 * Provides helper methods for acquiring a set of unique available ports. It
 * is not safe to simply call AvailablePort.getRandomAvailablePort several
 * times in a row without doing something to ensure that they are unique.
 * Although they are random, it is possible for subsequent calls to
 * getRandomAvailablePort to return the same integer, unless that port is put
 * into use before further calls to getRandomAvailablePort.
 *
 * TODO The current package is renamed from com.gemstone.gemfire.internal to avoid package sealing issue.
 */
public class AvailablePortHelper {
  
  /**
   * Returns array of unique randomly available tcp ports of specified count.
   */
  public static int[] getRandomAvailableTCPPorts(int count) {
    List<Keeper> list = getRandomAvailableTCPPortKeepers(count);
    int[] ports = new int[list.size()];
    int i = 0;
    for (Keeper k: list) {
      ports[i] = k.getPort();
      k.release();
      i++;
    }
    return ports;
  }
  public static List<Keeper> getRandomAvailableTCPPortKeepers(int count) {
    List<Keeper> result = new ArrayList<Keeper>();
    while (result.size() < count) {
      result.add(AvailablePort.getRandomAvailablePortKeeper(AvailablePort.SOCKET));
    }
    return result;
  }
  
  /**
   * Returns array of unique randomly available tcp ports of specified count.
   */
  public static int[] getRandomAvailableTCPPortsForDUnitSite(int count) {
    int site = 1;
    String hostName = System.getProperty("hostName");
    if(hostName != null && hostName.startsWith("host") && hostName.length() > 4) {
      site = Integer.parseInt(hostName.substring(4));
    }
    
    Set set = new HashSet();
    while (set.size() < count) {
      int port = AvailablePort.getRandomAvailablePortWithMod(AvailablePort.SOCKET,site);
      set.add(new Integer(port));
    }
    int[] ports = new int[set.size()];
    int i = 0;
    for (Iterator iter = set.iterator(); iter.hasNext();) {
      ports[i] = ((Integer) iter.next()).intValue();
      i++;
    }
    return ports;
  }
  
  
  /**
   * Returns array of unique randomly available tcp ports of specified count.
   */
  public static int getRandomAvailablePortForDUnitSite() {
    int site = 1;
    String hostName = System.getProperty("hostName");
    if(hostName != null && hostName.startsWith("host")) {
      if (hostName.length() > 4) {
        site = Integer.parseInt(hostName.substring(4));
      }
    }
      int port = AvailablePort.getRandomAvailablePortWithMod(AvailablePort.SOCKET,site);
      return port;
  }
  
  
  /**
   * Returns randomly available tcp port.
   */
  public static int getRandomAvailableTCPPort() {
    return getRandomAvailableTCPPorts(1)[0];
  }
  
  /**
   * Returns array of unique randomly available udp ports of specified count.
   */
  public static int[] getRandomAvailableUDPPorts(int count) {
    Set set = new HashSet();
    while (set.size() < count) {
      int port = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
      set.add(new Integer(port));
    }
    int[] ports = new int[set.size()];
    int i = 0;
    for (Iterator iter = set.iterator(); iter.hasNext();) {
      ports[i] = ((Integer) iter.next()).intValue();
      i++;
    }
    return ports;
  }
  
  /**
   * Returns randomly available udp port.
   */
  public static int getRandomAvailableUDPPort() {
    return getRandomAvailableUDPPorts(1)[0];
  }
  
}

