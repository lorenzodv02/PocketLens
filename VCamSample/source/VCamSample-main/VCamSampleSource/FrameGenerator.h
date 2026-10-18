#pragma once

class FrameGenerator
{
	UINT _width;
	UINT _height;
	ULONGLONG _frame;
	HANDLE _deviceHandle;
	HINTERNET _httpSession;
	HINTERNET _httpConnect;
	HINTERNET _frameStreamRequest;
	wil::com_ptr_nothrow<ID3D11Texture2D> _texture;
	wil::com_ptr_nothrow<ID3D11DeviceContext> _deviceContext;
	wil::com_ptr_nothrow<ID2D1RenderTarget> _renderTarget;
	wil::com_ptr_nothrow<ID2D1Bitmap> _cameraBitmap;
	wil::com_ptr_nothrow<IMFTransform> _converter;
	wil::com_ptr_nothrow<IWICBitmap> _bitmap;
	wil::com_ptr_nothrow<IWICImagingFactory> _wicFactory;
	wil::com_ptr_nothrow<IMFDXGIDeviceManager> _dxgiManager;
	std::thread _frameReader;
	std::mutex _frameMutex;
	std::atomic_bool _stopFrameReader;
	std::vector<BYTE> _latestJpeg;
	std::chrono::steady_clock::time_point _lastFrameAt;
	UINT _cameraRotation;
	uint64_t _jpegSequence;
	uint64_t _renderedJpegSequence;

	HRESULT CreateRenderTargetResources(UINT width, UINT height);
	HRESULT FetchCameraFrame(std::vector<BYTE>& jpeg);
	void ReadCameraFrames();
	HRESULT DrawCameraFrame();
	void ResetFrameStream();

public:
	FrameGenerator() :
		_width(0),
		_height(0),
		_frame(0),
		_deviceHandle(nullptr),
		_httpSession(nullptr),
		_httpConnect(nullptr),
		_frameStreamRequest(nullptr),
		_stopFrameReader(false),
		_lastFrameAt(),
		_cameraRotation(0),
		_jpegSequence(0),
		_renderedJpegSequence((std::numeric_limits<uint64_t>::max)())
	{
	}

	~FrameGenerator()
	{
		StopFrameReader();
		if (_dxgiManager && _deviceHandle)
		{
			auto hr = _dxgiManager->CloseDeviceHandle(_deviceHandle); // don't report error at that point
			if (FAILED(hr))
			{
				WINTRACE(L"FrameGenerator CloseDeviceHandle: 0x%08X", hr);
			}
		}
		if (_frameStreamRequest) WinHttpCloseHandle(_frameStreamRequest);
		if (_httpConnect) WinHttpCloseHandle(_httpConnect);
		if (_httpSession) WinHttpCloseHandle(_httpSession);
	}

	HRESULT SetD3DManager(IUnknown* manager, UINT width, UINT height);
	const bool HasD3DManager() const;
	HRESULT EnsureRenderTarget(UINT width, UINT height);
	void StartFrameReader();
	void StopFrameReader();
	HRESULT Generate(IMFSample* sample, REFGUID format, IMFSample** outSample);
};